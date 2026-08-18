package bank.tessera.ledger.adapter.jdbc;

import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountStatus;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.CustomerRef;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.OverdraftPolicy;
import bank.tessera.ledger.port.AccountRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The account port, against PostgreSQL, in hand-written SQL.
 *
 * <p>No JPA and no lazy loading. Every statement that touches money is readable in one place, which is
 * the only way a reviewer can tell what a transfer actually does to the database.
 */
public final class JdbcAccountRepository implements AccountRepository {

    private static final String COLUMNS =
            "reference, customer_ref, account_type, currency, status, overdraft_limit_minor";

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAccountRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Account> findByReference(AccountRef reference) {
        return findOne("SELECT " + COLUMNS + " FROM account WHERE reference = :reference", reference);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code SELECT ... FOR UPDATE}, which takes a row lock held until the transaction ends. Locking
     * more than one account goes through {@link AccountLocks}, never through repeated calls here in
     * whatever order the caller happens to have them - that is how two transfers deadlock.
     */
    @Override
    public Optional<Account> findForUpdate(AccountRef reference) {
        return findOne(
                "SELECT " + COLUMNS + " FROM account WHERE reference = :reference FOR UPDATE", reference);
    }

    @Override
    public Account save(Account account) {
        jdbc.update(
                """
                INSERT INTO account
                    (reference, customer_ref, account_type, currency, status, overdraft_limit_minor)
                VALUES
                    (:reference, :customer, :type, :currency, :status, :overdraftLimit)
                ON CONFLICT (reference) DO UPDATE SET
                    customer_ref          = EXCLUDED.customer_ref,
                    account_type          = EXCLUDED.account_type,
                    currency              = EXCLUDED.currency,
                    status                = EXCLUDED.status,
                    overdraft_limit_minor = EXCLUDED.overdraft_limit_minor,
                    updated_at            = now()
                """,
                // MapSqlParameterSource, not Map.of: a forbidden overdraft is a null limit, and Map.of
                // rejects null values outright.
                new MapSqlParameterSource()
                        .addValue("reference", account.reference().value())
                        .addValue("customer", account.customer().value())
                        .addValue("type", account.type().name())
                        .addValue("currency", account.currency().code())
                        .addValue("status", account.status().name())
                        .addValue("overdraftLimit", overdraftLimitOf(account)));

        // An account with no balance row is a hole every read would have to paper over, and a missing
        // row is indistinguishable from a genuine zero. It is opened here, with the account.
        jdbc.update(
                """
                INSERT INTO balance (account_ref, booked_minor, currency)
                VALUES (:reference, 0, :currency)
                ON CONFLICT (account_ref) DO NOTHING
                """,
                Map.of("reference", account.reference().value(), "currency", account.currency().code()));

        return account;
    }

    private Optional<Account> findOne(String sql, AccountRef reference) {
        List<Account> found =
                jdbc.query(sql, Map.of("reference", reference.value()), MAPPER);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * A null limit is {@link OverdraftPolicy#forbidden()}, never zero.
     *
     * <p>{@code upTo(zero)} is a different policy - an account allowed to reach exactly zero and no
     * further - and collapsing the two would quietly hand an overdraft of nothing to accounts that must
     * never have one. The column is nullable for this reason alone.
     */
    private static Long overdraftLimitOf(Account account) {
        return account.overdraft().isForbidden()
                ? null
                : account.overdraft().limitOr(account.currency()).amountMinor();
    }

    private static final RowMapper<Account> MAPPER = JdbcAccountRepository::mapAccount;

    private static Account mapAccount(ResultSet row, int rowNumber) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency"));
        long limit = row.getLong("overdraft_limit_minor");
        OverdraftPolicy overdraft = row.wasNull()
                ? OverdraftPolicy.forbidden()
                : OverdraftPolicy.upTo(Money.of(limit, currency));

        return Account.builder()
                .reference(AccountRef.of(row.getString("reference")))
                .customer(CustomerRef.of(row.getString("customer_ref")))
                .type(AccountType.valueOf(row.getString("account_type")))
                .currency(currency)
                .status(AccountStatus.valueOf(row.getString("status")))
                .overdraft(overdraft)
                .build();
    }
}
