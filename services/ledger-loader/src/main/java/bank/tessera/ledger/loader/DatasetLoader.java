package bank.tessera.ledger.loader;

import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.Direction;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.OverdraftPolicy;
import bank.tessera.ledger.loader.LedgerRows.AccountRow;
import bank.tessera.ledger.loader.LedgerRows.BalanceRow;
import bank.tessera.ledger.loader.LedgerRows.EntryRow;
import bank.tessera.ledger.loader.LedgerRows.PostingRow;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Turns a dataset stream into ledger rows.
 *
 * <p>The arithmetic lives here and the database does not, which is what lets every rule below be
 * tested in milliseconds against an in-memory sink and then executed unchanged against five million
 * postings.
 *
 * <p><strong>Nothing here restates a rule another module already holds.</strong> The signed effect of
 * a posting is {@code AccountType.signedEffect}'s, the money is {@code Money}'s, and the references
 * are validated by the domain's own types. A loader with its own copy of the sign convention would
 * produce a ledger that reconciles against itself and against nothing else.
 */
public class DatasetLoader implements DatasetVisitor {

    /**
     * How many times a cohort's median transfer an account is opened with.
     *
     * <p>A round number, and named as one. It has to be large enough that the year of transfers the
     * model draws is not refused for want of funds - at 200 the refusal counter reads zero on the
     * committed model - and small enough that the opening balances are the size of money the cohort
     * actually moves. It is not a claim about what a Polish retail customer holds.
     */
    static final long OPENING_MULTIPLE = 200;

    /** The audit actor. The same constant the API records, and for the same reason: F-29. */
    static final String ACTOR = "ledger-loader";

    private final RowSink sink;
    private final long checkpointEvery;
    private final EnumMap<Counter, Long> counters = new EnumMap<>(Counter.class);

    /**
     * What a customer account is allowed to do, which is not go below zero.
     *
     * <p>Read from the domain rather than written out as {@code newBalance >= 0}. Every account this
     * loader opens carries a null overdraft limit, and {@code OverdraftPolicy.forbidden()} is what
     * that column means - so the guard below asks the same object the API's transfer path asks.
     */
    private static final OverdraftPolicy CUSTOMER_POLICY = OverdraftPolicy.forbidden();

    private Header header;
    private CurrencyCode baseCurrency;
    private LocalDate currentDate;
    private long sinceCheckpoint;
    private long fundingOrdinal;

    /** Every account the estate holds, and what it is worth so far. */
    private final Map<String, AccountState> accounts = new LinkedHashMap<>();

    public DatasetLoader(RowSink sink, long checkpointEvery) {
        this.sink = Objects.requireNonNull(sink, "sink");
        if (checkpointEvery <= 0) {
            throw new IllegalArgumentException("A checkpoint every " + checkpointEvery + " rows is not one");
        }
        this.checkpointEvery = checkpointEvery;
    }

    /** The state a loader has to carry per account, and nothing more. */
    static final class AccountState {
        final String customerRef;
        final AccountType type;
        final String cohort;
        long bookedMinor;
        long postings;

        AccountState(String customerRef, AccountType type, String cohort) {
            this.customerRef = customerRef;
            this.type = type;
            this.cohort = cohort;
        }
    }

    @Override
    public void population(Header incoming) {
        if (header != null) {
            throw new IllegalStateException("The stream carries a second population header.");
        }
        header = incoming;
        baseCurrency = CurrencyCode.of(incoming.baseCurrency());
        currentDate = incoming.openingDate();
    }

    @Override
    public void open(OpenAccount opened) {
        requireHeader();
        String reference = opened.account().value();
        if (accounts.containsKey(reference)) {
            throw new IllegalStateException("The stream opens " + reference + " twice.");
        }
        AccountState state = new AccountState(opened.customer().value(), opened.type(), opened.cohort());
        accounts.put(reference, state);

        Instant openedAt = header.openingDate().atStartOfDay(ZoneOffset.UTC).toInstant();
        sink.account(new AccountRow(
                reference,
                state.customerRef,
                state.type.name(),
                baseCurrency.code(),
                "OPEN",
                // Null, not zero. V1 is explicit that the two are different statements and that
                // conflating them grants an overdraft of nothing to accounts that must never have one.
                null,
                openedAt,
                openedAt,
                header.openingDate()));
        count(Counter.ACCOUNTS_OPENED);

        if (!opened.treasury()) {
            fund(opened, openedAt);
        }
        checkpointIfDue();
    }

    /**
     * Posts an opening balance from the treasury.
     *
     * <p>A double-entry ledger has no way to type a number into a balance column, which is the whole
     * point of it: an opening balance is a transfer, debited from the bank's own account. The
     * treasury is an ASSET, so the debit increases it - the bank's claim grows as it funds its
     * customers - and it therefore never needs an overdraft.
     */
    private void fund(OpenAccount opened, Instant at) {
        long median = header.cohortMedians().getOrDefault(opened.cohort(), 0L);
        if (median <= 0) {
            throw new IllegalStateException(
                    "The header describes no median amount for cohort '" + opened.cohort()
                            + "', so " + opened.account() + " could not be opened with a balance.");
        }
        Money amount = Money.of(median * OPENING_MULTIPLE, baseCurrency);
        String reference = fundingReference();

        sink.entry(new EntryRow(reference, header.openingDate(), baseCurrency.code(), at, null, "OPENING BALANCE"));
        postLeg(reference, 1, header.treasuryAccountRef(), Direction.DEBIT, amount);
        postLeg(reference, 2, opened.account().value(), Direction.CREDIT, amount);
        count(Counter.ENTRIES);
        count(Counter.FUNDING_ENTRIES);
    }

    /**
     * Writes one leg and applies it to the account it lands on.
     *
     * <p>The only place in this module a balance moves, so the sign convention is consulted once
     * rather than reproduced per call site.
     */
    void postLeg(String entryRef, int seq, String accountRef, Direction direction, Money amount) {
        AccountState state = require(accountRef);
        sink.posting(new PostingRow(
                entryRef, seq, accountRef, direction.name(), amount.amountMinor(), amount.currency().code()));
        state.bookedMinor += state.type.signedEffect(direction, amount).amountMinor();
        state.postings++;
        count(Counter.POSTINGS);
    }

    @Override
    public void action(DrawnAction action) {
        requireHeader();
        if (!action.date().equals(currentDate)) {
            sink.checkpoint(currentDate);
            sinceCheckpoint = 0;
            currentDate = action.date();
        }
        switch (action.operation()) {
            case "createTransfer" -> transfer(action);
            default -> count(Counter.READS_IGNORED);
        }
    }

    /**
     * Posts a drawn transfer, or refuses it exactly as the ledger would have.
     *
     * <p>Two substitutions happen here and both are counted rather than hidden.
     *
     * <p>The estate is opened in one currency, so a transfer drawn in another goes in that one -
     * WP-21 made the same choice for the same reason, and F-72 records what it would take to resolve
     * properly. And an account that cannot cover the debit does not get one: nothing in the schema
     * stops a negative balance, so a loader that posted it anyway would be writing rows
     * {@code Transfer} would have rejected, and the DoD's "no constraint was disabled to complete a
     * load" would be true while the data was not.
     */
    private void transfer(DrawnAction action) {
        String debitRef = action.accountRef();
        String creditRef = action.counterpartyRef();
        if (debitRef.equals(creditRef)) {
            throw new IllegalStateException(
                    "The stream transfers " + debitRef + " to itself, which no drawn action can do.");
        }
        if (!baseCurrency.code().equals(action.currency())) {
            count(Counter.CURRENCY_SUBSTITUTED);
        }

        Money amount = Money.of(action.amountMinor(), baseCurrency);
        AccountState debit = require(debitRef);
        Money after = Money.of(
                debit.bookedMinor + debit.type.signedEffect(Direction.DEBIT, amount).amountMinor(),
                baseCurrency);
        if (!CUSTOMER_POLICY.permits(after)) {
            count(Counter.REFUSED_INSUFFICIENT_FUNDS);
            return;
        }

        String reference = action.transferRef();
        sink.entry(new EntryRow(reference, action.date(), baseCurrency.code(), action.at(), null, null));
        postLeg(reference, 1, debitRef, Direction.DEBIT, amount);
        postLeg(reference, 2, creditRef, Direction.CREDIT, amount);
        count(Counter.ENTRIES);
        count(Counter.TRANSFERS_POSTED);
    }

    /** Writes the materialised balances and closes the load. Every account gets a row. */
    public LoadSummary finish() {
        requireHeader();
        sink.checkpoint(currentDate);

        Instant at = header.to().atStartOfDay(ZoneOffset.UTC).toInstant();
        for (Map.Entry<String, AccountState> entry : accounts.entrySet()) {
            sink.balance(new BalanceRow(entry.getKey(), entry.getValue().bookedMinor, baseCurrency.code(), at));
        }
        sink.checkpoint(header.to());
        return new LoadSummary(header, Map.copyOf(counters), busiestAccount());
    }

    /**
     * The account the load gave the most postings to.
     *
     * <p>Reported rather than chosen: the deep-cursor query plan is captured against whatever the
     * draw actually produced, and an account planted to be deep would be a plan of the fixture this
     * package exists to replace.
     */
    private LoadSummary.Busiest busiestAccount() {
        LoadSummary.Busiest busiest = LoadSummary.Busiest.none();
        for (Map.Entry<String, AccountState> entry : accounts.entrySet()) {
            if (entry.getValue().postings > busiest.postings()) {
                busiest = new LoadSummary.Busiest(entry.getKey(), entry.getValue().postings);
            }
        }
        return busiest;
    }

    private String fundingReference() {
        // TB, the opening date, then a sequence - the format the canonical data model fixes for an
        // entry reference. The date is the day before the range, so a funding reference cannot
        // collide with a transfer reference the population minted on any date inside it.
        fundingOrdinal++;
        return String.format(
                "TB%04d%02d%02d%010d",
                header.openingDate().getYear(),
                header.openingDate().getMonthValue(),
                header.openingDate().getDayOfMonth(),
                fundingOrdinal);
    }

    AccountState require(String accountRef) {
        AccountState state = accounts.get(accountRef);
        if (state == null) {
            throw new IllegalStateException(
                    "The stream posts to " + accountRef + ", which it never opened.");
        }
        return state;
    }

    private void checkpointIfDue() {
        sinceCheckpoint++;
        if (sinceCheckpoint >= checkpointEvery) {
            sink.checkpoint(currentDate);
            sinceCheckpoint = 0;
        }
    }

    void count(Counter counter) {
        counters.merge(counter, 1L, Long::sum);
    }

    Map<Counter, Long> counters() {
        return counters;
    }

    Header header() {
        return header;
    }

    CurrencyCode baseCurrency() {
        return baseCurrency;
    }

    private void requireHeader() {
        if (header == null) {
            throw new IllegalStateException("The stream carries no population header.");
        }
    }
}
