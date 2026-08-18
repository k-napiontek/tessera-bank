package bank.tessera.ledger.adapter.jdbc;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.Money;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Proves that every materialised balance equals the sum of its postings.
 *
 * <p>The {@code balance} table is a read optimisation, and therefore a second source of truth. The
 * authority is the postings: they are append-only and immutable, so summing them cannot be wrong unless
 * the postings themselves are. This class derives every balance the slow way and compares.
 *
 * <p>That is the entire value of the exercise. If {@code balanceOf} summed the postings itself, this
 * routine would compare a number to itself and could never fail, and the materialised figure would be
 * trusted on the strength of a test that proves nothing. Two independent derivations that must agree is
 * what makes REQ-LED-006 a control.
 *
 * <p>It <strong>returns</strong> the drift rather than logging it. A routine that writes a warning
 * nobody reads has not reported anything, and the caller - a scheduled job, an operator tool, a test -
 * is what decides whether drift is an alert or an incident.
 */
public final class BalanceReconciliation {

    /**
     * One account where the two derivations disagree.
     *
     * @param account the account
     * @param materialised what the {@code balance} row says
     * @param summed what the postings add up to
     */
    public record Drift(AccountRef account, Money materialised, Money summed) {

        /** What the materialised figure is out by. Positive means it overstates the postings. */
        public Money difference() {
            return materialised.minus(summed);
        }

        @Override
        public String toString() {
            return account + ": balance says " + materialised.toPlainString()
                    + ", postings say " + summed.toPlainString()
                    + ", out by " + difference().toPlainString();
        }
    }

    private final NamedParameterJdbcTemplate jdbc;

    public BalanceReconciliation(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every account whose materialised balance disagrees with its postings.
     *
     * <p>An empty list is the expected result and the only acceptable one. Anything else is a break, and
     * a break is investigated rather than corrected - overwriting the balance from the postings would
     * destroy the evidence of how it drifted, which is the only thing that explains why.
     */
    public List<Drift> breaks() {
        // The signed effect is applied in SQL rather than in Java, because summing every posting in the
        // ledger through the application would defeat the point of a reconciliation run. The CASE
        // reproduces AccountType.signedEffect: an ASSET or EXPENSE rises on the debit, everything else
        // on the credit. That duplication is deliberate and it is the reason this check has teeth - two
        // implementations of the same rule that must agree, not one implementation checked against
        // itself. A test asserts the two stay in step.
        return jdbc.query(
                """
                SELECT b.account_ref,
                       b.currency,
                       b.booked_minor AS materialised,
                       coalesce(s.summed, 0) AS summed
                  FROM balance b
                  JOIN account a ON a.reference = b.account_ref
                  LEFT JOIN (
                        SELECT p.account_ref,
                               sum(CASE
                                     WHEN acc.account_type IN ('ASSET', 'EXPENSE')
                                       THEN CASE WHEN p.direction = 'DEBIT'
                                                 THEN p.amount_minor ELSE -p.amount_minor END
                                     ELSE CASE WHEN p.direction = 'CREDIT'
                                               THEN p.amount_minor ELSE -p.amount_minor END
                                   END) AS summed
                          FROM posting p
                          JOIN account acc ON acc.reference = p.account_ref
                         GROUP BY p.account_ref
                  ) s ON s.account_ref = b.account_ref
                 WHERE b.booked_minor <> coalesce(s.summed, 0)
                 ORDER BY b.account_ref
                """,
                Map.of(),
                (row, rowNumber) -> {
                    CurrencyCode currency = CurrencyCode.of(row.getString("currency").trim());
                    return new Drift(
                            AccountRef.of(row.getString("account_ref")),
                            Money.of(row.getLong("materialised"), currency),
                            Money.of(row.getLong("summed"), currency));
                });
    }
}
