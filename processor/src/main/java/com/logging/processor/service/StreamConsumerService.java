package com.logging.processor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logging.processor.model.LogEntity;
import com.logging.processor.model.LogMessage;
import com.logging.processor.repository.LogRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Consumes log messages from Redis Streams using consumer groups.
 * Implements batch inserts and ack-on-write: messages are acknowledged only after successful database persistence.
 * Tracks metrics for processing rate, DB write latency, and batch processing.
 */
@Slf4j
@Service
public class StreamConsumerService {
    
    private static final String STREAM_NAME = "logs:stream";
    private static final String CONSUMER_GROUP = "log-processors";
    private static final String CONSUMER_NAME = "processor-1";
    private static final int BATCH_SIZE = 50;
    private static final int MAX_PENDING_BATCHES = 3;
    private static final int BACKPRESSURE_THRESHOLD = MAX_PENDING_BATCHES * BATCH_SIZE;
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final LogRepository logRepository;
    private final ObjectMapper objectMapper;
    
    // Metrics
    private final Counter logsProcessedCounter;
    private final Counter batchesProcessedCounter;
    private final Timer dbWriteLatencyTimer;
    
    // Track pending database operations for backpressure handling
    private final AtomicInteger pendingDbOperations = new AtomicInteger(0);
    
    public StreamConsumerService(RedisTemplate<String, Object> redisTemplate,
                                 LogRepository logRepository,
                                 ObjectMapper objectMapper,
                                 MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.logRepository = logRepository;
        this.objectMapper = objectMapper;
        
        this.logsProcessedCounter = Counter.builder("logs.processed")
            .description("Total number of log messages processed and persisted")
            .tag("consumer_group", CONSUMER_GROUP)
            .register(meterRegistry);
        this.batchesProcessedCounter = Counter.builder("logs.batches.processed")
            .description("Total number of batches processed")
            .tag("consumer_group", CONSUMER_GROUP)
            .register(meterRegistry);
        this.dbWriteLatencyTimer = Timer.builder("logs.db.write.latency")
            .description("Time taken to write log batches to database")
            .tag("consumer_group", CONSUMER_GROUP)
            .register(meterRegistry);
    }
    
    @Scheduled(fixedDelay = 500)
    public void processStream() {
        try {
            StreamOperations<String, Object, Object> streamOps = redisTemplate.opsForStream();
            
            // Ensure consumer group exists
            ensureConsumerGroup(streamOps);
            
            // Check backpressure - pause consumption if database is overloaded
            if (pendingDbOperations.get() >= BACKPRESSURE_THRESHOLD) {
                log.warn("Backpressure: {} pending DB operations, pausing stream consumption", 
                    pendingDbOperations.get());
                return;
            }
            
            // Process pending messages first (retry on restart)
            processPendingMessages(streamOps);
            
            // Process new messages in batches
            processNewMessages(streamOps);
            
        } catch (Exception e) {
            log.error("Error processing stream", e);
        }
    }
    
    private void ensureConsumerGroup(StreamOperations<String, Object, Object> streamOps) {
        try {
            streamOps.createGroup(STREAM_NAME, CONSUMER_GROUP);
            log.debug("Consumer group {} created or already exists", CONSUMER_GROUP);
        } catch (Exception e) {
            // Group already exists, which is fine
            log.debug("Consumer group {} already exists", CONSUMER_GROUP);
        }
    }
    
    private void processPendingMessages(StreamOperations<String, Object, Object> streamOps) {
        try {
            PendingMessagesSummary pending = streamOps.pending(STREAM_NAME, CONSUMER_GROUP);
            if (pending.getTotalPendingMessages() == 0) {
                return;
            }
            
            log.info("Processing {} pending messages", pending.getTotalPendingMessages());
            
            // Read pending messages for this consumer
            List<MapRecord<String, Object, Object>> pendingRecords = streamOps.read(
                Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                StreamReadOptions.empty().count(BATCH_SIZE),
                StreamOffset.create(STREAM_NAME, ReadOffset.from("0-0"))
            );
            
            if (pendingRecords == null || pendingRecords.isEmpty()) {
                return;
            }
            
            // Process pending messages in batch
            processBatch(pendingRecords, streamOps);
            
        } catch (Exception e) {
            // Ignore if no pending messages or stream doesn't exist yet
            if (!e.getMessage().contains("NOGROUP") && !e.getMessage().contains("no such key")) {
                log.debug("No pending messages or error reading pending: {}", e.getMessage());
            }
        }
    }
    
    private void processNewMessages(StreamOperations<String, Object, Object> streamOps) {
        try {
            List<MapRecord<String, Object, Object>> records = streamOps.read(
                Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                StreamReadOptions.empty().count(BATCH_SIZE),
                StreamOffset.create(STREAM_NAME, ReadOffset.lastConsumed())
            );
            
            if (records == null || records.isEmpty()) {
                return;
            }
            
            // Process new messages in batch
            processBatch(records, streamOps);
            
        } catch (Exception e) {
            if (!e.getMessage().contains("NOGROUP")) {
                log.error("Error reading new messages", e);
            }
        }
    }
    
    /**
     * Processes a batch of messages: converts to entities, saves to database in batch,
     * then acknowledges all messages after successful write (ack-on-write).
     */
    @Transactional
    private void processBatch(List<MapRecord<String, Object, Object>> records, 
                             StreamOperations<String, Object, Object> streamOps) {
        if (records.isEmpty()) {
            return;
        }
        
        pendingDbOperations.addAndGet(records.size());
        
        try {
            List<LogEntity> entities = new ArrayList<>();
            List<RecordId> recordIds = new ArrayList<>();
            
            // Convert all messages to entities
            for (MapRecord<String, Object, Object> record : records) {
                try {
                    String jsonPayload = extractJsonPayload(record.getValue());
                    LogMessage logMessage = objectMapper.readValue(jsonPayload, LogMessage.class);
                    LogEntity entity = convertToEntity(logMessage);
                    entities.add(entity);
                    recordIds.add(record.getId());
                } catch (Exception e) {
                    log.error("Failed to convert message {}", record.getId(), e);
                    // Skip this message but continue with batch
                }
            }
            
            if (entities.isEmpty()) {
                pendingDbOperations.addAndGet(-records.size());
                return;
            }
            
            // Batch insert to database with latency tracking
            dbWriteLatencyTimer.record(() -> {
                logRepository.saveAll(entities);
            });
            
            // Update metrics
            logsProcessedCounter.increment(entities.size());
            batchesProcessedCounter.increment();
            
            // Acknowledge all messages after successful batch write (ack-on-write)
            for (RecordId recordId : recordIds) {
                try {
                    streamOps.acknowledge(STREAM_NAME, CONSUMER_GROUP, recordId);
                } catch (Exception e) {
                    log.error("Failed to acknowledge message {}", recordId, e);
                }
            }
            
            log.debug("Processed and acknowledged batch of {} messages", entities.size());
            
        } catch (Exception e) {
            log.error("Failed to process batch of {} messages", records.size(), e);
            // Messages remain unacknowledged and will be retried
        } finally {
            pendingDbOperations.addAndGet(-records.size());
        }
    }
    
    private String extractJsonPayload(Map<Object, Object> values) {
        // Redis stream stores the JSON payload under the "payload" key
        Object payload = values.get("payload");
        if (payload instanceof String) {
            return (String) payload;
        }
        // Fallback: try to find any string value
        for (Object value : values.values()) {
            if (value instanceof String) {
                return (String) value;
            }
        }
        throw new IllegalArgumentException("No JSON payload found in stream values");
    }
    
    private LogEntity convertToEntity(LogMessage logMessage) {
        LogEntity entity = new LogEntity();
        entity.setTs(Instant.parse(logMessage.getTs()));
        entity.setApp(logMessage.getApp());
        entity.setLevel(logMessage.getLevel());
        entity.setMsg(logMessage.getMsg());
        
        try {
            if (logMessage.getFields() != null && !logMessage.getFields().isEmpty()) {
                entity.setFields(objectMapper.writeValueAsString(logMessage.getFields()));
            }
        } catch (Exception e) {
            log.warn("Failed to serialize fields", e);
        }
        
        return entity;
    }
}
