package bank.tessera.ledger.loader;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The rows the loader writes, one record per table, named and typed as WP-07's migrations declare
 * them.
 *
 * <p>They are records rather than a wide {@code Object[]} because a bulk loader's whole risk is
 * writing a plausible value into the wrong column: {@code COPY} takes positional text and
 * PostgreSQL will happily accept a currency code in a status column if both are {@code varchar}.
 * Naming every field once, here, is what makes the writer's column order checkable by reading it.
 *
 * <p>Money is {@code long} minor units throughout. There is no {@code double} and no
 * {@code BigDecimal} on this path, in this module or in the schema it writes to.
 */
public final class LedgerRows {

    private LedgerRows() {}

    /**
     * @param overdraftLimitMinor null means {@code OverdraftPolicy.forbidden()}, which is a different
     *     statement from a limit of zero - V1 says so in the column comment and it is why this is a
     *     boxed Long rather than a long
     */
    public record AccountRow(
            String reference,
            String customerRef,
            String accountType,
            String currency,
            String status,
            Long overdraftLimitMinor,
            Instant createdAt,
            Instant updatedAt,
            LocalDate openedDate) {}

    public record EntryRow(
            String reference,
            LocalDate valueDate,
            String currency,
            Instant createdAt,
            String reverses,
            String referenceText) {}

    /** Amount always positive: direction carries the sign, exactly as MOVEREC does at stratum 0. */
    public record PostingRow(
            String entryRef, int seq, String accountRef, String direction, long amountMinor, String currency) {}

    public record BalanceRow(String accountRef, long bookedMinor, String currency, Instant updatedAt) {}

    public record HoldRow(
            String reference,
            String accountRef,
            long amountMinor,
            String currency,
            String status,
            Instant placedAt,
            Instant expiresAt,
            String capturedBy,
            Instant transitionedAt) {}

    public record AuditRow(
            Instant occurredAt,
            String actor,
            String action,
            String subjectRef,
            String correlationId,
            String beforeState,
            String afterState,
            String previousHash,
            String hash) {}
}
