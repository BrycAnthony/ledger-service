package com.ledger.health;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbcTemplate) {
        // Dedicated copy so the short timeout applies only to health probes,
        // not to the shared JdbcTemplate used by application code.
        this.jdbc = new JdbcTemplate(jdbcTemplate.getDataSource());
        this.jdbc.setQueryTimeout(2);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        try {
            // A real round-trip query, not just "does the pool exist": a pool
            // can hold stale connections to a database that has gone away.
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "UP", "database", "UP"));
        } catch (DataAccessException e) {
            // Details go to logs only; the response body stays generic.
            log.warn("Health check failed: database unreachable", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "DOWN", "database", "DOWN"));
        }
    }
}
