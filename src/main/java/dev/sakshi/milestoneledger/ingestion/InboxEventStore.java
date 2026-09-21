package dev.sakshi.milestoneledger.ingestion;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** One durable acceptance path for signed webhook facts and trusted snapshot recovery facts. */
@Service
public class InboxEventStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public InboxEventStore(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    public int insert(UUID id, String source, String eventId, UUID projectId, String account,
                      String receiptId, long amount, String currency, String reference, Instant postedAt,
                      String hash, String origin) {
        Instant now = clock.instant();
        return jdbc.update("""
                insert into inbox_event (id, source, event_id, project_id, account_reference, bank_receipt_id,
                    amount_paise, currency, demand_reference, posted_at, canonical_hash,
                    next_attempt_at, received_at, origin)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (source, event_id) do nothing
                """, id, source, eventId, projectId, account, receiptId, amount, currency, reference,
                Timestamp.from(ReceiptFacts.normalize(postedAt)), hash, Timestamp.from(now), Timestamp.from(now), origin);
    }
}
