package com.logging.producer.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logging.producer.model.LogMessage;
import lombok.RequiredArgsConstructor;
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
 * Uses the logs:stream stream for all log entries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StreamPublisherService {
    
    private static final String STREAM_NAME = "logs:stream";
    
    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    
    public RecordId publish(LogMessage logMessage) {
        try {
            String jsonPayload = objectMapper.writeValueAsString(logMessage);
            StringRecord record = StreamRecords.newRecord()
                .ofStrings(Collections.singletonMap("payload", jsonPayload))
                .withStreamKey(STREAM_NAME);
            
            StreamOperations<String, String, String> streamOps = redisTemplate.opsForStream();
            return streamOps.add(record);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize log message", e);
            throw new RuntimeException("Failed to publish log message", e);
        }
    }
}

