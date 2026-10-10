package com.vyoog.importqueue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * VYB-0940: {@link ExtractionProgress} on {@code import_batch} and {@code import_extraction_step} (V051). Every write is its own
 * short transaction (REQUIRES_NEW), because the whole point is that a step is on record before the next model call starts.
 */
@Service
public class JdbcExtractionProgress implements ExtractionProgress {

    private static final Logger log = LoggerFactory.getLogger(JdbcExtractionProgress.class);

    /** Longer than the slowest extraction can take (batch deadline per call, many calls), so a live one is never taken over. */
    static final Duration STALE_AFTER = Duration.ofMinutes(30);

    private static final int REASON_MAX = 1000;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate independently;
    private final ObjectMapper json;

    @Autowired
    public JdbcExtractionProgress(JdbcTemplate jdbc, PlatformTransactionManager transactions, ObjectMapper json) {
        this.jdbc = jdbc;
        this.independently = new TransactionTemplate(transactions);
        this.independently.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // A saved reply may carry derived properties (a record's isWellFormed) that the record cannot take back in.
        this.json = json.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @Override
    public Optional<String> claim(UUID batchId) {
        return independently.execute(status -> {
            String previous = jdbc.query("SELECT state FROM import_batch WHERE id = ? FOR UPDATE",
                rs -> rs.next() ? rs.getString(1) : null, batchId);
            if (previous == null) throw new java.util.NoSuchElementException("No such import batch");
            if ("EXTRACTING".equals(previous)) {
                Boolean stale = jdbc.queryForObject(
                    "SELECT extraction_started_at IS NULL OR extraction_started_at < now() - make_interval(secs => ?) FROM import_batch WHERE id = ?",
                    Boolean.class, (double) STALE_AFTER.toSeconds(), batchId);
                if (!Boolean.TRUE.equals(stale)) return Optional.<String>empty();
                log.warn("[extraction] taking over a stale extraction of batch {}", batchId);
                previous = "EXTRACTION_FAILED";
            }
            jdbc.update("UPDATE import_batch SET state = 'EXTRACTING', extraction_started_at = now(), extraction_error = NULL WHERE id = ?", batchId);
            return Optional.of(previous);
        });
    }

    @Override
    public ExtractionSteps steps(UUID batchId, String sourceDigest) {
        independently.executeWithoutResult(status ->
            jdbc.update("DELETE FROM import_extraction_step WHERE batch_id = ? AND source_digest <> ?", batchId, sourceDigest));
        return new ExtractionSteps() {
            @Override public <T> Optional<T> load(String key, Class<T> type) {
                String stored = jdbc.query("SELECT result::text FROM import_extraction_step WHERE batch_id = ? AND step_key = ? AND source_digest = ?",
                    rs -> rs.next() ? rs.getString(1) : null, batchId, key, sourceDigest);
                if (stored == null) return Optional.empty();
                try {
                    return Optional.of(json.readValue(stored, type));
                } catch (Exception e) {
                    // A step that cannot be read is made again; it is never guessed.
                    log.warn("[extraction] saved step {} of batch {} could not be read ({}); it will be made again", key, batchId, e.getMessage());
                    return Optional.empty();
                }
            }

            @Override public <T> void save(String key, T value) {
                String text;
                try {
                    text = json.writeValueAsString(value);
                } catch (Exception e) {
                    log.warn("[extraction] step {} of batch {} could not be saved ({}); extraction continues without it", key, batchId, e.getMessage());
                    return;
                }
                independently.executeWithoutResult(status -> jdbc.update("""
                    INSERT INTO import_extraction_step (batch_id, step_key, source_digest, result) VALUES (?, ?, ?, ?::jsonb)
                    ON CONFLICT (batch_id, step_key) DO UPDATE SET source_digest = EXCLUDED.source_digest, result = EXCLUDED.result, saved_at = now()
                    """, batchId, key, sourceDigest, text));
            }
        };
    }

    @Override
    public void fail(UUID batchId, String reason, String previousState) {
        String text = reason == null || reason.isBlank() ? "Extraction failed." : reason.length() > REASON_MAX ? reason.substring(0, REASON_MAX) : reason;
        String next = previousState == null || "UPLOADED".equals(previousState) || "EXTRACTION_FAILED".equals(previousState)
            ? "EXTRACTION_FAILED" : previousState;
        independently.executeWithoutResult(status ->
            jdbc.update("UPDATE import_batch SET state = ?, extraction_error = ? WHERE id = ? AND state = 'EXTRACTING'", next, text, batchId));
    }

    @Override
    public void complete(UUID batchId) {
        // Runs inside the caller's transaction, so the steps go with the candidates that replace them, or not at all.
        jdbc.update("DELETE FROM import_extraction_step WHERE batch_id = ?", batchId);
        jdbc.update("UPDATE import_batch SET state = 'EXTRACTED', extraction_error = NULL WHERE id = ?", batchId);
    }
}
