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
import bank.tessera.ledger.port.LedgerReadModel;
import bank.tessera.ledger.port.Movement;
import bank.tessera.ledger.port.StatementPage;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** The keyset statement query, against real PostgreSQL. */
class JdbcStatementPageTest {

    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final AccountRef BANK = AccountRef.of("TB00000000000000");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);

    private static LedgerReadModel readModel;
    private static JdbcAccountRepository accounts;
    private static JdbcJournalEntryRepository entries;

    /** Every test gets its own account and its own references, so none can observe another's writes. */
    private static final AtomicInteger NEXT_ACCOUNT = new AtomicInteger();
    private static final AtomicInteger NEXT_ENTRY = new AtomicInteger();

    private AccountRef customer;

    @BeforeAll
    static void migrate() {
        DataSource dataSource = PostgresSupport.migratedSchema("statement_page");
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(dataSource);
        readModel = new JdbcLedgerReadModel(jdbc);
        accounts = new JdbcAccountRepository(jdbc);
        entries = new JdbcJournalEntryRepository(
                jdbc, new JdbcHoldRepository(jdbc), Transactions.of(dataSource));
        accounts.save(account(BANK, AccountType.ASSET));
    }

    @BeforeEach
    void openAnAccountOfItsOwn() {
        customer = AccountRef.of(String.format("TB%014d", NEXT_ACCOUNT.incrementAndGet()));
        accounts.save(account(customer, AccountType.LIABILITY));
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

    /** The customer account is a LIABILITY of the bank, so a CREDIT increases what it holds. */
    private EntryRef credit(LocalDate valueDate, long minor) {
        EntryRef reference = EntryRef.of(String.format("TB20260819%010d", NEXT_ENTRY.incrementAndGet()));
        entries.append(JournalEntry.of(
                reference,
                valueDate,
                List.of(
                        Posting.of(BANK, Direction.DEBIT, Money.of(minor, PLN)),
                        Posting.of(customer, Direction.CREDIT, Money.of(minor, PLN)))));
        return reference;
    }

    /** Six credits of 10.00, on six consecutive value dates. Booked balance afterwards: 60.00. */
    private void sixCredits() {
        for (int day = 1; day <= 6; day++) {
            credit(LocalDate.of(2026, 6, day), 10_00);
        }
    }

    private StatementPage page(String cursor, int limit) {
        return readModel.statementPage(customer, FROM, TO, cursor, limit);
    }

    private static Money closing(StatementPage page) {
        Money running = page.openingBalance();
        for (Movement movement : page.movements()) {
            running =
                    running.plus(AccountType.LIABILITY.signedEffect(movement.direction(), movement.amount()));
        }
        return running;
    }

    @Test
    @DisplayName("a page foots: opening plus its own movements equals closing")
    void aPageFoots() {
        sixCredits();

        StatementPage page = page(null, 4);

        assertThat(page.movements()).hasSize(4);
        assertThat(page.openingBalance()).isEqualTo(Money.zero(PLN));
        assertThat(closing(page)).isEqualTo(Money.of(40_00, PLN));
    }

    @Test
    @DisplayName("one page's closing balance is the next page's opening balance")
    void thePagesChain() {
        sixCredits();

        StatementPage first = page(null, 4);
        StatementPage second = page(first.nextCursor().orElseThrow(), 4);

        // This is the property that makes page-scoped balances worth the extra query. Without it a
        // reader has to fetch every page and concatenate before the arithmetic can be checked at all.
        assertThat(second.openingBalance()).isEqualTo(closing(first));
        assertThat(second.openingBalance()).isEqualTo(Money.of(40_00, PLN));
        assertThat(closing(second)).isEqualTo(Money.of(60_00, PLN));
    }

    @Test
    @DisplayName("the last page reports no next cursor")
    void theLastPageEndsTheChain() {
        sixCredits();

        StatementPage first = page(null, 4);
        StatementPage second = page(first.nextCursor().orElseThrow(), 4);

        assertThat(first.nextCursor()).isPresent();
        assertThat(second.movements()).hasSize(2);
        assertThat(second.nextCursor()).isEmpty();
    }

    @Test
    @DisplayName("walking every page returns each movement exactly once, oldest first")
    void thePagesCoverTheRangeExactlyOnce() {
        sixCredits();

        List<Movement> walked = walk(2, null);

        assertThat(walked).hasSize(6);
        assertThat(walked.stream().map(Movement::movementReference).toList()).doesNotHaveDuplicates();
        assertThat(walked.stream().map(Movement::valueDate).toList()).isSorted();
    }

    @Test
    @DisplayName("a movement posted between two page reads is neither skipped nor duplicated")
    void aConcurrentInsertCannotSkipOrDuplicate() {
        sixCredits();

        // The defect an offset produces, made reachable on purpose. Read page one, then post a
        // movement that sorts BEFORE everything already read - an earlier value date - and read on.
        // With OFFSET 2 the rows behind the boundary shift down: one already-returned movement comes
        // back a second time and one that was never returned is lost. With a keyset the boundary is
        // a value rather than a position, so an insert behind it changes nothing about what follows.
        StatementPage first = page(null, 2);
        List<Movement> read = new ArrayList<>(first.movements());

        credit(LocalDate.of(2026, 5, 1), 99_00);

        List<Movement> rest = walk(2, first.nextCursor().orElseThrow());
        read.addAll(rest);

        assertThat(read.stream().map(Movement::movementReference).toList()).doesNotHaveDuplicates();
        assertThat(read.stream().map(Movement::valueDate).toList()).isSorted();
        // The four that had not been read yet, and nothing else. The interloper sorts behind the
        // cursor, so it correctly never appears on a later page.
        assertThat(rest).hasSize(4);
    }

    @Test
    @DisplayName("a range that excludes everything returns an empty page that still states the balance")
    void anEmptyRangeStillFoots() {
        sixCredits();

        StatementPage september = readModel.statementPage(
                customer, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, 10);

        assertThat(september.movements()).isEmpty();
        assertThat(september.nextCursor()).isEmpty();
        // Everything before September has posted, so the range opens - and closes - at that balance.
        // Reporting zero here would tell a customer their account was empty in September.
        assertThat(september.openingBalance()).isEqualTo(Money.of(60_00, PLN));
        assertThat(closing(september)).isEqualTo(september.openingBalance());
    }

    @Test
    @DisplayName("a statement opens at the balance the range inherits, not at zero")
    void arangeOpensAtWhatCameBefore() {
        sixCredits();

        StatementPage july = readModel.statementPage(
                customer, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31), null, 10);

        assertThat(july.openingBalance()).isEqualTo(Money.of(60_00, PLN));
    }

    @Test
    @DisplayName("a cursor this ledger did not issue is rejected, not interpreted")
    void aForeignCursorIsRejected() {
        assertThatThrownBy(() -> page("not-a-cursor", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not issued by this ledger");

        // Well-formed base64 that decodes to the wrong shape is refused just as firmly, and the
        // message says nothing about what it decoded to.
        assertThatThrownBy(() -> page("MjAyNi0wNi0wMQ", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not issued by this ledger");
    }

    @Test
    @DisplayName("a page limit below one is refused rather than silently treated as unbounded")
    void aNonPositiveLimitIsRefused() {
        assertThatThrownBy(() -> page(null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
    }

    private List<Movement> walk(int limit, String from) {
        List<Movement> seen = new ArrayList<>();
        String cursor = from;
        do {
            StatementPage current = page(cursor, limit);
            seen.addAll(current.movements());
            cursor = current.nextCursor().orElse(null);
        } while (cursor != null);
        return seen;
    }
}
