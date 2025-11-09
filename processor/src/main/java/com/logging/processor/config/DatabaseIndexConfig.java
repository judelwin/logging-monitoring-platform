package com.logging.processor.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ensures database indexes are created, including the descending timestamp index.
 * Executes after JPA schema initialization.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseIndexConfig {
    
    private final JdbcTemplate jdbcTemplate;
    
    @PostConstruct
    public void createIndexes() {
        try {
            // Create descending index on timestamp for efficient time-based queries
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_logs_ts_desc ON logs(ts DESC)");
            log.info("Created descending index on logs.ts");
        } catch (Exception e) {
            log.warn("Failed to create indexes (they may already exist): {}", e.getMessage());
        }
    }
}

