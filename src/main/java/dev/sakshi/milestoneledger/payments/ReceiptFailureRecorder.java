package dev.sakshi.milestoneledger.payments;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReceiptFailureRecorder {
    private static final Logger LOG = LoggerFactory.getLogger(ReceiptFailureRecorder.class);
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public ReceiptFailureRecorder(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID eventId) {
        record(eventId, false);
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID eventId, boolean nonRetryable) {
        java.util.List<FailureState> changed = jdbc.query("""
                update inbox_event
                set attempt_count = attempt_count + 1,
                    status = case when ? or attempt_count + 1 >= 5 then 'FAILED' else 'PENDING' end,
                    next_attempt_at = ?::timestamptz + (power(2, attempt_count)::integer * interval '1 second'),
                    last_error_code = ?
                where id = ? and status = 'PENDING'
                returning attempt_count, status, next_attempt_at
                """, (rs, n) -> new FailureState(rs.getInt("attempt_count"), rs.getString("status"),
                rs.getTimestamp("next_attempt_at").toInstant()), nonRetryable, Timestamp.from(clock.instant()),
                nonRetryable ? "INVALID_EVENT_FACTS" : "PROCESSING_FAILED", eventId);
        if (!changed.isEmpty()) {
            FailureState state = changed.getFirst();
            LOG.warn("Receipt processing failed inboxEventId={} attemptCount={} status={} nextAttemptAt={} errorCode={}",
                    eventId, state.attemptCount(), state.status(), state.nextAttemptAt(),
                    nonRetryable ? "INVALID_EVENT_FACTS" : "PROCESSING_FAILED");
        }
    }
    private record FailureState(int attemptCount, String status, Instant nextAttemptAt) { }
}
