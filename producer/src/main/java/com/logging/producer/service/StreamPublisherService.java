package com.logging.producer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logging.producer.model.LogMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.stereotype.Service;

import java.util.Collections;

/**
 * Publishes log messages to Redis Streams.
 * Tracks metrics for ingestion rate and publish latency.
 */
@Slf4j
@Service
public class StreamPublisherService {
    
    private static final String STREAM_NAME = "logs:stream";
    
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final Counter logsPublishedCounter;
    private final Timer publishLatencyTimer;
    
    public StreamPublisherService(RedisTemplate<String, String> redisTemplate, 
                                  ObjectMapper objectMapper, 
                                  MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.logsPublishedCounter = Counter.builder("logs.published")
            .description("Total number of log messages published to Redis Streams")
            .tag("stream", STREAM_NAME)
            .register(meterRegistry);
        this.publishLatencyTimer = Timer.builder("logs.publish.latency")
            .description("Time taken to publish log messages to Redis Streams")
            .tag("stream", STREAM_NAME)
            .register(meterRegistry);
    }
    
    public RecordId publish(LogMessage logMessage) {
        return publishLatencyTimer.record(() -> {
            try {
                String jsonPayload = objectMapper.writeValueAsString(logMessage);
                StringRecord record = StreamRecords.newRecord()
                    .ofStrings(Collections.singletonMap("payload", jsonPayload))
                    .withStreamKey(STREAM_NAME);
                
                StreamOperations<String, String, String> streamOps = redisTemplate.opsForStream();
                RecordId recordId = streamOps.add(record);
                
                // Increment counter after successful publish
                logsPublishedCounter.increment();
                
                return recordId;
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize log message", e);
                throw new RuntimeException("Failed to publish log message", e);
            }
        });
    }
}

