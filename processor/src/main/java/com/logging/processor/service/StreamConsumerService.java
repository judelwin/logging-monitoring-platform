package com.logging.processor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logging.processor.model.LogEntity;
import com.logging.processor.model.LogMessage;
import com.logging.processor.repository.LogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Consumes log messages from Redis Streams using consumer groups.
 * Implements ack-on-write: messages are acknowledged only after successful database persistence.
 * Handles pending messages on restart by processing unacknowledged entries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamConsumerService {
    
    private static final String STREAM_NAME = "logs:stream";
    private static final String CONSUMER_GROUP = "log-processors";
    private static final String CONSUMER_NAME = "processor-1";
    private static final int BATCH_SIZE = 10;
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final LogRepository logRepository;
    private final ObjectMapper objectMapper;
    
    @Scheduled(fixedDelay = 1000)
    public void processStream() {
        try {
            StreamOperations<String, Object, Object> streamOps = redisTemplate.opsForStream();
            
            // Ensure consumer group exists
            ensureConsumerGroup(streamOps);
            
            // Process pending messages first (retry on restart)
            processPendingMessages(streamOps);
            
            // Process new messages
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
            
            // Process each pending message
            for (MapRecord<String, Object, Object> record : pendingRecords) {
                processMessage(record.getId(), record.getValue(), streamOps);
            }
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
            
            for (MapRecord<String, Object, Object> record : records) {
                processMessage(record.getId(), record.getValue(), streamOps);
            }
        } catch (Exception e) {
            if (!e.getMessage().contains("NOGROUP")) {
                log.error("Error reading new messages", e);
            }
        }
    }
    
    private void processMessage(RecordId recordId, Map<Object, Object> values, StreamOperations<String, Object, Object> streamOps) {
        try {
            // Extract JSON payload from stream values
            String jsonPayload = extractJsonPayload(values);
            LogMessage logMessage = objectMapper.readValue(jsonPayload, LogMessage.class);
            
            // Persist to database
            LogEntity logEntity = convertToEntity(logMessage);
            logRepository.save(logEntity);
            
            // Acknowledge only after successful write (ack-on-write)
            streamOps.acknowledge(STREAM_NAME, CONSUMER_GROUP, recordId);
            
            log.debug("Processed and acknowledged message: {}", recordId);
            
        } catch (Exception e) {
            log.error("Failed to process message {}", recordId, e);
            // Message remains unacknowledged and will be retried
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

