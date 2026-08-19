package bank.tessera.ledger.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.CustomerRef;
import bank.tessera.ledger.domain.Direction;
import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.JournalEntry;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.OverdraftPolicy;
import bank.tessera.ledger.domain.Posting;
import bank.tessera.ledger.port.Movement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Paging a statement, and the footing identity that makes a page checkable on its own. */
class GetStatementTest {

    private static final AccountRef CUSTOMER = AccountRef.of("TB00000000000001");
    private static final AccountRef BANK = AccountRef.of("TB00000000000002");
    private static final AccountRef UNKNOWN = AccountRef.of("TB00000000009999");
    private static final CurrencyCode PLN = CurrencyCode.of("PLN");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 12, 31);
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-19T09:00:00Z"), ZoneOffset.UTC);

    private InMemoryLedger ledger;
    private InMemoryLedger.SequentialReferences references;
    private GetStatement getStatement;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryLedger();
        references = new InMemoryLedger.SequentialReferences();
        OpenAccount openAccount =
                new OpenAccount(ledger.accounts, ledger.readModel, ledger.unitOfWork, ledger.auditTrail(FIXED), FIXED);
        openAccount.open(open(CUSTOMER, AccountType.LIABILITY));
        openAccount.open(open(BANK, AccountType.ASSET));
        getStatement = new GetStatement(ledger.accounts, ledger.readModel, ledger.unitOfWork);
    }

    private OpenAccount.Command open(AccountRef reference, AccountType type) {
        return new OpenAccount.Command(
                reference, CustomerRef.of("CU0000000001"), type, PLN, null, OverdraftPolicy.forbidden());
    }

    /** A credit into a LIABILITY account increases what the bank owes the customer. */
    private void credit(LocalDate valueDate, long minor) {
        EntryRef reference = references.nextEntryReference();
        ledger.entries.append(JournalEntry.of(
                reference,
                valueDate,
                List.of(
                        Posting.of(BANK, Direction.DEBIT, Money.of(minor, PLN)),
                        Posting.of(CUSTOMER, Direction.CREDIT, Money.of(minor, PLN)))));
        // The fake stamps Instant.EPOCH on append; the sort falls through to the entry reference,
        // which advances, so the order is still the order they were posted in.
        ledger.postedAtByEntry.put(reference, Instant.parse("2026-08-19T09:00:00Z"));
    }

    private void sixCredits() {
        for (int day = 1; day <= 6; day++) {
            credit(LocalDate.of(2026, 6, day), 10_00);
        }
    }

    private Money footed(StatementView page) {
        Money running = page.openingBalance();
        for (Movement movement : page.movements()) {
            running = running.plus(AccountType.LIABILITY.signedEffect(movement.direction(), movement.amount()));
        }
        return running;
    }

    @Test
    @DisplayName("a page foots: opening plus its own movements equals the closing it reports")
    void aPageFoots() {
        sixCredits();

        StatementView page = getStatement.of(CUSTOMER, FROM, TO, null, 4).orElseThrow();

        assertThat(page.movements()).hasSize(4);
        assertThat(page.openingBalance()).isEqualTo(Money.zero(PLN));
        assertThat(page.closingBalance()).isEqualTo(Money.of(40_00, PLN));
        assertThat(page.closingBalance()).isEqualTo(footed(page));
    }

    @Test
    @DisplayName("one page's closing balance is the next page's opening balance")
    void thePagesChain() {
        sixCredits();

        StatementView first = getStatement.of(CUSTOMER, FROM, TO, null, 4).orElseThrow();
        StatementView second = getStatement
                .of(CUSTOMER, FROM, TO, first.nextCursor().orElseThrow(), 4)
                .orElseThrow();

        assertThat(second.openingBalance()).isEqualTo(first.closingBalance());
        assertThat(second.closingBalance()).isEqualTo(Money.of(60_00, PLN));
        assertThat(second.nextCursor()).isEmpty();
    }

    @Test
    @DisplayName("the range is echoed back on every page, not just the first")
    void everyPageEchoesTheRange() {
        sixCredits();

        StatementView first = getStatement.of(CUSTOMER, FROM, TO, null, 4).orElseThrow();
        StatementView second = getStatement
                .of(CUSTOMER, FROM, TO, first.nextCursor().orElseThrow(), 4)
                .orElseThrow();

        assertThat(second.from()).isEqualTo(FROM);
        assertThat(second.to()).isEqualTo(TO);
        assertThat(second.account()).isEqualTo(CUSTOMER);
    }

    @Test
    @DisplayName("a debit on the same account moves the closing balance the other way")
    void directionSignsTheClosingBalance() {
        credit(LocalDate.of(2026, 6, 1), 100_00);
        EntryRef reference = references.nextEntryReference();
        ledger.entries.append(JournalEntry.of(
                reference,
                LocalDate.of(2026, 6, 2),
                List.of(
                        Posting.of(CUSTOMER, Direction.DEBIT, Money.of(30_00, PLN)),
                        Posting.of(BANK, Direction.CREDIT, Money.of(30_00, PLN)))));

        StatementView page = getStatement.of(CUSTOMER, FROM, TO, null, 10).orElseThrow();

        // A LIABILITY account falls on a debit. Signing every movement the same way would produce a
        // closing balance of 130.00 and a statement that looks entirely plausible.
        assertThat(page.closingBalance()).isEqualTo(Money.of(70_00, PLN));
    }

    @Test
    @DisplayName("an empty range still reports the balance the account carries into it")
    void anEmptyRangeReportsTheInheritedBalance() {
        sixCredits();

        StatementView page = getStatement
                .of(CUSTOMER, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null, 10)
                .orElseThrow();

        assertThat(page.movements()).isEmpty();
        assertThat(page.openingBalance()).isEqualTo(Money.of(60_00, PLN));
        assertThat(page.closingBalance()).isEqualTo(Money.of(60_00, PLN));
    }

    @Test
    @DisplayName("an unknown account has no statement, which is not an empty one")
    void anUnknownAccountIsAbsent() {
        assertThat(getStatement.of(UNKNOWN, FROM, TO, null, 10)).isEmpty();
    }

    @Test
    @DisplayName("an inverted range is refused rather than quietly returning nothing")
    void anInvertedRangeIsRefused() {
        assertThatThrownBy(() -> getStatement.of(CUSTOMER, TO, FROM, null, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ends before it begins");
    }

    @Test
    @DisplayName("a limit outside the contract's bounds is refused at the use case, not at the edge")
    void anOutOfBoundsLimitIsRefused() {
        assertThatThrownBy(() -> getStatement.of(CUSTOMER, FROM, TO, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> getStatement.of(CUSTOMER, FROM, TO, null, GetStatement.MAXIMUM_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 500");
    }
}
