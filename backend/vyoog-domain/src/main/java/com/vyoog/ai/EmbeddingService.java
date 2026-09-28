package com.vyoog.ai;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * VYB-0600/0601/0603. {@code requirement_embedding} (V001__baseline.sql) has a native
 * pgvector column — mapped here as raw SQL with the value bound as its
 * {@code '[0.1,0.2,...]'::vector} text literal, the same "anything JPQL/Hibernate
 * can't express cleanly goes through JdbcTemplate" rule this codebase already applies
 * to recursive trace-graph queries, rather than adding a Hibernate user-type for one
 * column.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final EmbeddingProvider provider;
    private final JdbcTemplate jdbc;
    private final AiUsageTracker aiUsage;

    public EmbeddingService(EmbeddingProvider provider, JdbcTemplate jdbc, AiUsageTracker aiUsage) {
        this.provider = provider;
        this.jdbc = jdbc;
        this.aiUsage = aiUsage;
    }

    private record ExistingEmbedding(int revision, String model) {}

    /**
     * VYB-0601 AC1/AC2: called on every material create/update — re-embeds only when
     * this exact (revision, model) pair hasn't already been embedded, so calling this
     * twice for the same save is a no-op, and so is a request that arrives after the
     * requirement has already moved past this revision.
     *
     * <p>VYB-0603: a provider failure is caught and logged, never thrown back at the
     * caller — the requirement write that triggered this must not appear to have
     * failed because an embedding couldn't be computed.
     */
    public void embed(UUID requirementId, int revision, String statement) {
        try {
            ExistingEmbedding existing = jdbc.query(
                "SELECT revision, model FROM requirement_embedding WHERE requirement_id = ?",
                rs -> rs.next() ? new ExistingEmbedding(rs.getInt("revision"), rs.getString("model")) : null,
                requirementId);
            if (existing != null && existing.revision() == revision && provider.modelName().equals(existing.model())) {
                return; // already embedded at exactly this revision, with the current model
            }

            float[] vector = provider.embed(statement);
            String literal = toVectorLiteral(vector);
            // Session 14: a bare Instant here threw PSQLException at real-database time
            // (pgjdbc's 2-arg setObject can't infer a SQL type for it — same root cause
            // as AuditService.record's bug, fixed the same way). Worse here than there:
            // this whole call is wrapped in `catch (Exception e) { log.warn(...) }` two
            // lines below, so the failure was never loud — every embedding write this
            // method ever attempted against a real Postgres would have silently not
            // persisted, logged as an unremarkable warning indistinguishable from a
            // genuinely unavailable provider.
            jdbc.update("""
                INSERT INTO requirement_embedding (requirement_id, revision, model, embedding, embedded_at)
                VALUES (?, ?, ?, ?::vector, ?)
                ON CONFLICT (requirement_id) DO UPDATE
                  SET revision = EXCLUDED.revision, model = EXCLUDED.model,
                      embedding = EXCLUDED.embedding, embedded_at = EXCLUDED.embedded_at
                """, requirementId, revision, provider.modelName(), literal, Timestamp.from(Instant.now()));
        } catch (AiProviderUnavailableException e) {
            log.warn("[embedding] provider unavailable for {}: {}", requirementId, e.getMessage());
        } catch (Exception e) {
            log.warn("[embedding] failed for {}: {}", requirementId, e.getMessage());
        }
    }

    static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    /**
     * VYB-0604/0620: re-embed the whole corpus when the configured model changes.
     * Unlike {@link #embed}, called once per requirement write and never throttled
     * (each is one write's own hygiene, not a burst), this walks potentially the
     * entire register in one call — it's exactly the kind of bursty AI cost VYB-0620
     * means to bound, so it stops (defers the rest) once the per-run budget is spent,
     * rather than failing outright (AC1) — a second call later finishes the job.
     *
     * @return how many requirements were actually re-embedded before the budget ran out.
     */
    public int reembedStaleModel() {
        var stale = jdbc.query("""
            SELECT r.id, r.revision, r.statement FROM requirement r
            LEFT JOIN requirement_embedding re ON re.requirement_id = r.id
            WHERE r.deleted_at IS NULL AND (re.requirement_id IS NULL OR re.model <> ?)
            """,
            (rs, n) -> new Object[]{UUID.fromString(rs.getString("id")), rs.getInt("revision"), rs.getString("statement")},
            provider.modelName());

        int done = 0;
        for (Object[] row : stale) {
            if (!aiUsage.tryConsume()) break; // VYB-0620 AC1: defer the rest, don't fail the call
            embed((UUID) row[0], (Integer) row[1], (String) row[2]);
            done++;
        }
        return done;
    }
}
