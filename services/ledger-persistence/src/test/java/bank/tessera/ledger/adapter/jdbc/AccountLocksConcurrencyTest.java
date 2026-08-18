package bank.tessera.ledger.adapter.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bank.tessera.ledger.domain.Account;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountStatus;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.CustomerRef;
import bank.tessera.ledger.domain.Direction;
import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.JournalEntry;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.OverdraftPolicy;
import bank.tessera.ledger.domain.Posting;
import bank.tessera.ledger.port.JournalEntryRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The test this whole package exists for.
 *
 * <p>Threads move money around a ring of accounts, in both directions over the same pairs. The
 * invariant is that <strong>total value across the ring never changes</strong>: a transfer takes from
 * one account exactly what it gives another, so however the threads interleave, the sum is what it
 * started as. A lost update breaks it, and so does a partially applied entry.
 *
 * <p>Real PostgreSQL is not an implementation detail here. An in-memory database does not take
 * {@code SELECT ... FOR UPDATE} row locks, so this test would pass against one while proving nothing -
 * the worst possible outcome, since it would then be cited as evidence.
 */
class AccountLocksConcurrencyTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final LocalDate VALUE_DATE = LocalDate.of(2026, 8, 18);
    private static final int RING_SIZE = 5;
    private static final int THREADS = 6;
    private static final int TRANSFERS_PER_THREAD = 25;
    private static final long OPENING_BALANCE_MINOR = 1_000_00;

    private static final AtomicInteger ENTRY_SEQUENCE = new AtomicInteger();

    private static List<AccountRef> ring;
    private static JournalEntryRepository entries;
    private static AccountLocks locks;
    private static TransactionTemplate transactions;
    private static NamedParameterJdbcTemplate jdbc;

    @BeforeAll
    static void seedTheRing() {
        DataSource dataSource = PostgresSupport.migratedSchema("locking");
        jdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAccountRepository accounts = new JdbcAccountRepository(jdbc);
        transactions = Transactions.of(dataSource);
        entries = new JdbcJournalEntryRepository(jdbc, new JdbcHoldRepository(jdbc), transactions);
        locks = new AccountLocks(accounts);

        AccountRef funding = AccountRef.of("TB00000000000900");
        accounts.save(account(funding, AccountType.ASSET));

        List<AccountRef> accountRefs = new ArrayList<>();
        for (int index = 1; index <= RING_SIZE; index++) {
            AccountRef reference = AccountRef.of(String.format("TB000000000009%02d", index));
            accounts.save(account(reference, AccountType.LIABILITY));
            accountRefs.add(reference);
        }
        ring = List.copyOf(accountRefs);

        // Funding: debit the asset, credit the customer account. Value enters the ring here and never
        // leaves it, so the ring's total is fixed from this point on.
        for (AccountRef reference : ring) {
            entries.append(entry(funding, reference, OPENING_BALANCE_MINOR));
        }
    }

    @Test
    @DisplayName("accounts are locked in ascending reference order whatever order they are asked for")
    void locksAreTakenInAscendingOrder() {
        List<AccountRef> reversed = new ArrayList<>(ring);
        Collections.reverse(reversed);

        Map<AccountRef, Account> locked =
                transactions.execute(status -> locks.lockInOrder(reversed));

        // Asserted directly rather than inferred from the absence of a deadlock: a property that only
        // shows up as a flaky failure under load is a property no test can be trusted to hold.
        assertThat(locked.keySet()).containsExactlyElementsOf(ring);
    }

    @Test
    @DisplayName("a duplicate reference is locked once, not twice")
    void duplicatesAreCollapsed() {
        AccountRef first = ring.get(0);

        Map<AccountRef, Account> locked =
                transactions.execute(status -> locks.lockInOrder(List.of(first, first)));

        assertThat(locked).hasSize(1);
    }

    @Test
    @DisplayName("locking an account that does not exist fails rather than returning a short map")
    void lockingAnAbsentAccountFails() {
        assertThatThrownBy(() -> transactions.execute(status ->
                        locks.lockInOrder(List.of(ring.get(0), AccountRef.of("TB00000000000999")))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TB00000000000999");
    }

    @Test
    @DisplayName("concurrent transfers around a ring conserve total value, with no deadlock")
    void theRingConservesValue() throws Exception {
        long before = totalAcrossTheRing();
        assertThat(before).isEqualTo(RING_SIZE * OPENING_BALANCE_MINOR);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Callable<Integer>> work = new ArrayList<>();
        for (int thread = 0; thread < THREADS; thread++) {
            work.add(AccountLocksConcurrencyTest::runTransfers);
        }

        List<Future<Integer>> results = pool.invokeAll(work, 120, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS))
                .as("threads did not finish - a deadlock that PostgreSQL failed to break would look "
                        + "exactly like this")
                .isTrue();

        int applied = 0;
        List<String> failures = new ArrayList<>();
        for (Future<Integer> result : results) {
            try {
                applied += result.get();
            } catch (Exception failure) {
                // A deadlock surfaces as SQLState 40P01, "deadlock detected". PostgreSQL breaks the
                // cycle by aborting one transaction, so the symptom is a failed transfer - never a hang.
                failures.add(String.valueOf(failure.getCause()));
            }
        }

        assertThat(failures)
                .as("every transfer must succeed; a deadlock would appear here as SQLState 40P01")
                .isEmpty();
        assertThat(applied).isEqualTo(THREADS * TRANSFERS_PER_THREAD);
        assertThat(totalAcrossTheRing())
                .as("total value across the ring must be exactly what it started as")
                .isEqualTo(before);
    }

    // ------------------------------------------------------------------------------------------

    private static int runTransfers() {
        int applied = 0;
        for (int transfer = 0; transfer < TRANSFERS_PER_THREAD; transfer++) {
            int from = ThreadLocalRandom.current().nextInt(RING_SIZE);
            int to = (from + 1) % RING_SIZE;
            // Adjacent pairs in both directions, which is what puts two threads on the same pair in
            // opposite orders - the arrangement that deadlocks without a deterministic lock order.
            if (ThreadLocalRandom.current().nextBoolean()) {
                int swap = from;
                from = to;
                to = swap;
            }
            long amount = ThreadLocalRandom.current().nextLong(1_00, 50_00);
            transferOnce(ring.get(from), ring.get(to), amount);
            applied++;
        }
        return applied;
    }

    /** One transfer, the way WP-08's service will do it: lock in order, then append inside the same transaction. */
    private static void transferOnce(AccountRef from, AccountRef to, long amountMinor) {
        transactions.executeWithoutResult(status -> {
            locks.lockInOrder(List.of(from, to));
            entries.append(entry(from, to, amountMinor));
        });
    }

    private static JournalEntry entry(AccountRef debited, AccountRef credited, long amountMinor) {
        return JournalEntry.of(
                EntryRef.of(String.format("TB20260818%010d", ENTRY_SEQUENCE.incrementAndGet())),
                VALUE_DATE,
                List.of(
                        Posting.of(debited, Direction.DEBIT, Money.of(amountMinor, PLN)),
                        Posting.of(credited, Direction.CREDIT, Money.of(amountMinor, PLN))));
    }

    private static long totalAcrossTheRing() {
        Long total = jdbc.queryForObject(
                "SELECT coalesce(sum(booked_minor), 0) FROM balance WHERE account_ref IN (:refs)",
                Map.of("refs", ring.stream().map(AccountRef::value).toList()),
                Long.class);
        return total == null ? 0L : total;
    }

    private static Account account(AccountRef reference, AccountType type) {
        return Account.builder()
                .reference(reference)
                .customer(CustomerRef.of("CU0000000001"))
                .type(type)
                .currency(PLN)
                .status(AccountStatus.OPEN)
                .overdraft(OverdraftPolicy.forbidden())
                .build();
    }
}
