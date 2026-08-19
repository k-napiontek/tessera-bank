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
import bank.tessera.ledger.port.AccountDates;
import bank.tessera.ledger.port.LedgerReadModel;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcLedgerReadModelTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final AccountRef ALICE = AccountRef.of("TB00000000000001");
    private static final AccountRef BOB = AccountRef.of("TB00000000000002");

    private static LedgerReadModel readModel;
    private static JdbcAccountRepository accounts;
    private static JdbcJournalEntryRepository entries;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = PostgresSupport.migratedSchema("read_model");
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        readModel = new JdbcLedgerReadModel(jdbc);
        accounts = new JdbcAccountRepository(jdbc);
        entries = new JdbcJournalEntryRepository(
                jdbc, new JdbcHoldRepository(jdbc), Transactions.of(dataSource));

        accounts.save(account(ALICE));
        accounts.save(account(BOB));
    }

    private static Account account(AccountRef reference) {
        return Account.builder()
                .reference(reference)
                .customer(CustomerRef.of("CU0000000001"))
                .type(AccountType.LIABILITY)
                .currency(PLN)
                .status(AccountStatus.OPEN)
                .overdraft(OverdraftPolicy.forbidden())
                .build();
    }

    @Test
    @DisplayName("an opening date is a business date, recorded and read back unchanged")
    void anOpeningDateRoundTrips() {
        readModel.recordAccountOpened(ALICE, LocalDate.of(1998, 4, 15));

        AccountDates dates = readModel.accountDates(ALICE).orElseThrow();

        // Deliberately long before the row was inserted. If the adapter derived openedDate from
        // created_at this assertion would report today, which is exactly the confusion the separate
        // column exists to prevent for accounts migrated in from the mainframe.
        assertThat(dates.opened()).isEqualTo(LocalDate.of(1998, 4, 15));
    }

    @Test
    @DisplayName("last movement date is absent until something posts, then tracks the latest value date")
    void lastMovementFollowsThePostings() {
        // An account of its own, because every other test in this class posts against ALICE and BOB
        // and JUnit does not promise the order it runs them in. A test that only passes when it runs
        // first is a test that will fail for a reason nobody can reproduce.
        AccountRef carol = AccountRef.of("TB00000000000003");
        accounts.save(account(carol));
        readModel.recordAccountOpened(carol, LocalDate.of(2026, 1, 1));

        assertThat(readModel.accountDates(carol).orElseThrow().lastMovement()).isEmpty();

        entries.append(entry(carol, "TB202608190000000001", LocalDate.of(2026, 5, 2), 10_00));
        entries.append(entry(carol, "TB202608190000000002", LocalDate.of(2026, 6, 9), 25_00));
        entries.append(entry(carol, "TB202608190000000003", LocalDate.of(2026, 5, 20), 5_00));

        // The latest value date, not the latest posted. The last entry appended is dated 20 May and
        // must not win: a statement ordered by insertion would tell a customer their account last
        // moved on a day it did not.
        assertThat(readModel.accountDates(carol).orElseThrow().lastMovement())
                .contains(LocalDate.of(2026, 6, 9));
    }

    @Test
    @DisplayName("an unknown account has no dates at all, which is not the same as no movement")
    void anUnknownAccountIsAbsent() {
        assertThat(readModel.accountDates(AccountRef.of("TB00000000009999"))).isEmpty();
    }

    @Test
    @DisplayName("recording an opening date for an account that does not exist is refused")
    void recordingAgainstAMissingAccountThrows() {
        assertThatThrownBy(() ->
                        readModel.recordAccountOpened(AccountRef.of("TB00000000009999"), LocalDate.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    @DisplayName("an entry reports when the ledger recorded it")
    void anEntryReportsItsPostingInstant() {
        Instant before = Instant.now().minusSeconds(5);
        entries.append(entry("TB202608190000000010", LocalDate.of(2026, 7, 1), 1_00));

        Instant postedAt = readModel.entryPostedAt(EntryRef.of("TB202608190000000010")).orElseThrow();

        assertThat(postedAt).isAfter(before);
        assertThat(readModel.entryPostedAt(EntryRef.of("TB202608190000099999"))).isEmpty();
    }

    @Test
    @DisplayName("nothing reverses an entry until a reversal names it")
    void reversedByIsEmptyUntilThereIsAReversal() {
        entries.append(entry("TB202608190000000020", LocalDate.of(2026, 7, 2), 3_00));

        // WP-08 task 7 supplies the use case that writes journal_entry.reverses. Until then the only
        // honest answer is that nothing has reversed it, and the column proves that rather than the
        // absence of a feature quietly implying it.
        assertThat(readModel.reversedBy(EntryRef.of("TB202608190000000020"))).isEmpty();
    }

    private static JournalEntry entry(String reference, LocalDate valueDate, long minor) {
        return entry(BOB, reference, valueDate, minor);
    }

    private static JournalEntry entry(
            AccountRef credited, String reference, LocalDate valueDate, long minor) {
        return JournalEntry.of(
                EntryRef.of(reference),
                valueDate,
                List.of(
                        Posting.of(ALICE, Direction.DEBIT, Money.of(minor, PLN)),
                        Posting.of(credited, Direction.CREDIT, Money.of(minor, PLN))));
    }
}
