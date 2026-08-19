package bank.tessera.ledger.adapter.jdbc;

import bank.tessera.ledger.port.IdempotencyConflictException;
import bank.tessera.ledger.port.IdempotencyStore;
import bank.tessera.ledger.port.StoredResponse;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The idempotency store, against PostgreSQL.
 *
 * <p>The interesting statement is the one in {@link #claim}, and every part of it is deliberate.
 *
 * <p><strong>{@code ON CONFLICT (key) DO UPDATE}, not {@code DO NOTHING}.</strong> {@code DO NOTHING}
 * does not wait for a conflicting row that another transaction has inserted but not yet committed -
 * it skips, and the statement reports that nothing was inserted while the following {@code SELECT}
 * cannot see the uncommitted row either. Both retries would then conclude the key was theirs.
 * {@code DO UPDATE} takes a row lock and blocks until the other transaction finishes, which is
 * exactly the serialisation this table exists to provide. The update itself is a no-op assignment;
 * the lock is the point.
 *
 * <p><strong>{@code xmax = 0} distinguishes an insert from an update.</strong> An upsert cannot
 * otherwise say which of the two it did, and the caller must know whether to do the work or to
 * replay. A freshly inserted row has no deleting transaction, so its {@code xmax} is zero; a row the
 * conflict clause updated carries one. Obscure, and the alternative is a second round trip that
 * reopens the race the first statement just closed.
 */
public final class JdbcIdempotencyStore implements IdempotencyStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcIdempotencyStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public Optional<StoredResponse> claim(String key, String fingerprint) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(fingerprint, "fingerprint");

        List<Claim> claims = jdbc.query(
                """
                INSERT INTO idempotency_record (key, fingerprint)
                VALUES (:key, :fingerprint)
                ON CONFLICT (key) DO UPDATE SET key = idempotency_record.key
                RETURNING (xmax = 0) AS is_new, fingerprint, status, response_body
                """,
                new MapSqlParameterSource().addValue("key", key).addValue("fingerprint", fingerprint),
                (row, rowNumber) -> new Claim(
                        row.getBoolean("is_new"),
                        row.getString("fingerprint"),
                        row.getObject("status", Integer.class),
                        row.getString("response_body")));

        Claim claim = claims.get(0);
        if (claim.isNew()) {
            return Optional.empty();
        }
        if (!claim.fingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(key);
        }
        if (claim.status() == null) {
            // The row is committed but carries no response. idempotency_response_complete makes that
            // impossible on commit, so reaching here means the schema was changed out from under this
            // adapter. Failing loudly beats replaying a response that does not exist.
            throw new IllegalStateException(
                    "Idempotency record was committed without a response. The schema constraint is missing.");
        }
        return Optional.of(StoredResponse.of(claim.status(), claim.responseBody()));
    }

    @Override
    public void store(String key, StoredResponse response) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(response, "response");

        int updated = jdbc.update(
                """
                UPDATE idempotency_record
                   SET status = :status, response_body = :body
                 WHERE key = :key
                """,
                new MapSqlParameterSource()
                        .addValue("status", response.status())
                        .addValue("body", response.body())
                        .addValue("key", key));
        if (updated != 1) {
            throw new IllegalStateException(
                    "Cannot store a response against an idempotency key that was never claimed.");
        }
    }

    private record Claim(boolean isNew, String fingerprint, Integer status, String responseBody) {}
}
