package com.logging.processor.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Log message DTO for deserializing from Redis Stream.
 * Matches the producer's LogMessage schema.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogMessage {
    @JsonProperty("ts")
    private String ts;
    
    @JsonProperty("app")
    private String app;
    
    @JsonProperty("level")
    private String level;
    
    @JsonProperty("msg")
    private String msg;
    
    @JsonProperty("fields")
    private Map<String, Object> fields;
}

