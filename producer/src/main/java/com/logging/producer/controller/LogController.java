package com.logging.producer.controller;

import com.logging.producer.model.LogMessage;
import com.logging.producer.service.StreamPublisherService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for accepting and publishing log messages.
 * Publishes logs to Redis Streams for processing.
 */
@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
public class LogController {
    
    private final StreamPublisherService streamPublisherService;
    
    @PostMapping
    public ResponseEntity<LogPublishResponse> publishLog(@RequestBody LogMessage logMessage) {
        RecordId recordId = streamPublisherService.publish(logMessage);
        return ResponseEntity.ok(new LogPublishResponse(recordId.getValue()));
    }
    
    public record LogPublishResponse(String recordId) {}
}

