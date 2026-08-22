package bank.tessera.ledger.loader;

import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.Direction;
import bank.tessera.ledger.domain.Money;
import bank.tessera.ledger.domain.OverdraftPolicy;
import bank.tessera.ledger.loader.LedgerRows.AccountRow;
import bank.tessera.ledger.loader.LedgerRows.BalanceRow;
import bank.tessera.ledger.loader.LedgerRows.EntryRow;
import bank.tessera.ledger.loader.LedgerRows.HoldRow;
import bank.tessera.ledger.loader.LedgerRows.PostingRow;
import bank.tessera.ledger.port.AuditAction;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
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
public final class DatasetLoader implements DatasetVisitor {

    /** The audit actor. The same constant the API records, and for the same reason: F-29. */
    static final String ACTOR = "ledger-loader";

    private final RowSink sink;
    private final long checkpointEvery;
    private final ChainWriter chain = new ChainWriter(ACTOR);
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

    /**
     * Every hold, written once at the end in whatever state it finished in.
     *
     * <p>A hold row is inserted and then transitioned, which is an UPDATE - and a bulk loader that
     * updated rows it had just COPYed would be doing the work twice. Holding the final state here and
     * writing it once is the same trick the materialised balances use.
     */
    private final Map<String, HoldState> holds = new LinkedHashMap<>();

    /** The holds still PLACED on each account, oldest first. A capture or a release takes the oldest. */
    private final Map<String, Deque<String>> openHolds = new LinkedHashMap<>();

    /** The most recent transfer on each account that nothing has reversed yet. */
    private final Map<String, PostedTransfer> reversible = new LinkedHashMap<>();

    public DatasetLoader(RowSink sink, long checkpointEvery) {
        this.sink = Objects.requireNonNull(sink, "sink");
        if (checkpointEvery <= 0) {
            throw new IllegalArgumentException("A checkpoint every " + checkpointEvery + " rows is not one");
        }
        this.checkpointEvery = checkpointEvery;
    }

    /** A hold, from placement to whatever ended it. */
    static final class HoldState {
        final String accountRef;
        final long amountMinor;
        final Instant placedAt;
        String status = "PLACED";
        String capturedBy;
        Instant transitionedAt;

        HoldState(String accountRef, long amountMinor, Instant placedAt) {
            this.accountRef = accountRef;
            this.amountMinor = amountMinor;
            this.placedAt = placedAt;
        }
    }

    /** What a reversal needs to know about the entry it reverses. */
    record PostedTransfer(String entryRef, String debitRef, String creditRef, long amountMinor) {}

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
        // The same state map OpenAccount writes, so a loaded account's audit row is the row the API
        // would have left. A report joining on subject_ref cannot tell the two apart, which is the
        // point of loading rather than of inventing.
        audit(openedAt, AuditAction.ACCOUNT_OPENED, reference, Map.of(
                "customerRef", state.customerRef,
                "accountType", state.type.name(),
                "currency", baseCurrency.code(),
                "status", "OPEN",
                "openedDate", header.openingDate().toString()));

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
     *
     * <p>The figure comes off the wire and is not computed here. <strong>F-98.</strong> It used to be
     * a cohort median scaled by a constant this class owned, which made a loaded ledger disagree with
     * a driven one about every balance in the bank.
     */
    private void fund(OpenAccount opened, Instant at) {
        Money amount = Money.of(header.openingBalanceMinor(), baseCurrency);
        String reference = fundingReference();

        sink.entry(new EntryRow(reference, header.openingDate(), baseCurrency.code(), at, null, "OPENING BALANCE"));
        postLeg(reference, 1, header.treasuryAccountRef(), Direction.DEBIT, amount);
        postLeg(reference, 2, opened.account().value(), Direction.CREDIT, amount);
        count(Counter.ENTRIES);
        count(Counter.FUNDING_ENTRIES);
        transferPosted(reference, at, header.openingDate(), header.treasuryAccountRef(),
                opened.account().value(), amount);
    }

    /** The audit row a posted transfer leaves, whatever posted it. */
    private void transferPosted(
            String reference, Instant at, LocalDate valueDate, String debitRef, String creditRef, Money amount) {
        audit(at, AuditAction.TRANSFER_POSTED, reference, Map.of(
                "debitAccountRef", debitRef,
                "creditAccountRef", creditRef,
                "amountMinor", String.valueOf(amount.amountMinor()),
                "currency", amount.currency().code(),
                "valueDate", valueDate.toString()));
    }

    private void audit(Instant at, AuditAction action, String subject, Map<String, String> after) {
        audit(at, action, subject, Map.of(), after);
    }

    private void audit(
            Instant at, AuditAction action, String subject, Map<String, String> before, Map<String, String> after) {
        sink.audit(chain.next(at, action, subject, before, after));
        count(Counter.AUDIT_ROWS);
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
            case "reverseTransfer" -> reverse(action);
            case "placeHold" -> placeHold(action);
            case "captureHold" -> captureHold(action);
            case "releaseHold" -> releaseHold(action);
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
        transferPosted(reference, action.at(), action.date(), debitRef, creditRef, amount);
        reversible.put(debitRef, new PostedTransfer(reference, debitRef, creditRef, amount.amountMinor()));
    }

    /**
     * Reverses the account's most recent unreversed transfer.
     *
     * <p>A correction in a double-entry ledger is a reversing entry, never an edit - which is what
     * lets {@code posting} be append-only in the schema and the audit trail be a chain rather than a
     * diff. The entry that is reversed is removed from the reversible set here as well, because
     * {@code journal_entry_reverses_uq} makes reversing one twice a unique-index violation rather
     * than a second correction.
     */
    private void reverse(DrawnAction action) {
        PostedTransfer original = reversible.remove(action.accountRef());
        if (original == null) {
            count(Counter.NOTHING_TO_REVERSE);
            return;
        }

        Money amount = Money.of(original.amountMinor(), baseCurrency);
        // The mirror: whoever was credited is now debited, and it is that account which has to be
        // able to cover it. A reversal is refused for want of funds exactly as a transfer is.
        AccountState debit = require(original.creditRef());
        Money after = Money.of(
                debit.bookedMinor + debit.type.signedEffect(Direction.DEBIT, amount).amountMinor(),
                baseCurrency);
        if (!CUSTOMER_POLICY.permits(after)) {
            count(Counter.REFUSED_INSUFFICIENT_FUNDS);
            return;
        }

        String reference = action.transferRef();
        sink.entry(new EntryRow(
                reference, action.date(), baseCurrency.code(), action.at(), original.entryRef(), null));
        postLeg(reference, 1, original.creditRef(), Direction.DEBIT, amount);
        postLeg(reference, 2, original.debitRef(), Direction.CREDIT, amount);
        count(Counter.ENTRIES);
        count(Counter.TRANSFERS_REVERSED);
        audit(action.at(), AuditAction.TRANSFER_REVERSED, reference, Map.of(
                "reversesTransferRef", original.entryRef(),
                "reason", "LOADED",
                "valueDate", action.date().toString()));
    }

    /**
     * Reserves an amount against an account without posting anything.
     *
     * <p>Holds are here because the balance read is one of the query plans this package has to
     * capture, and {@code Balance.of} is the booked balance less the active holds. A plan measured
     * against a ledger holding no holds would be a plan of the fixture.
     */
    private void placeHold(DrawnAction action) {
        String reference = action.holdRef();
        if (reference == null || holds.containsKey(reference)) {
            count(Counter.HOLD_NOT_FOUND);
            return;
        }
        require(action.accountRef());
        holds.put(reference, new HoldState(action.accountRef(), action.amountMinor(), action.at()));
        openHolds.computeIfAbsent(action.accountRef(), account -> new ArrayDeque<>()).addLast(reference);
        count(Counter.HOLDS_PLACED);
        audit(action.at(), AuditAction.HOLD_PLACED, reference, Map.of(
                "accountRef", action.accountRef(),
                "amountMinor", String.valueOf(action.amountMinor()),
                "currency", baseCurrency.code(),
                "status", "PLACED"));
    }

    /**
     * Captures the oldest open hold on the account.
     *
     * <p>The capture is for the smaller of the amount drawn and the amount reserved, because
     * {@code CaptureHold} permits a partial capture and refuses one that exceeds the hold: capturing
     * more than was authorised is a different transaction, not a larger one.
     */
    private void captureHold(DrawnAction action) {
        String reference = takeOpenHold(action.accountRef());
        if (reference == null) {
            count(Counter.HOLD_NOT_FOUND);
            return;
        }
        HoldState hold = holds.get(reference);
        long capturedMinor = Math.min(action.amountMinor(), hold.amountMinor);
        if (capturedMinor <= 0 || action.counterpartyRef() == null) {
            count(Counter.HOLD_NOT_FOUND);
            return;
        }

        Money amount = Money.of(capturedMinor, baseCurrency);
        AccountState debit = require(hold.accountRef);
        Money after = Money.of(
                debit.bookedMinor + debit.type.signedEffect(Direction.DEBIT, amount).amountMinor(),
                baseCurrency);
        if (!CUSTOMER_POLICY.permits(after)) {
            count(Counter.REFUSED_INSUFFICIENT_FUNDS);
            // The hold stays open: nothing captured it, so saying it was captured would be a lie
            // the hold_captured_by_consistent constraint could not catch.
            openHolds.computeIfAbsent(hold.accountRef, account -> new ArrayDeque<>()).addFirst(reference);
            return;
        }

        String entryRef = action.transferRef();
        sink.entry(new EntryRow(entryRef, action.date(), baseCurrency.code(), action.at(), null, null));
        postLeg(entryRef, 1, hold.accountRef, Direction.DEBIT, amount);
        postLeg(entryRef, 2, action.counterpartyRef(), Direction.CREDIT, amount);
        count(Counter.ENTRIES);

        hold.status = "CAPTURED";
        hold.capturedBy = entryRef;
        hold.transitionedAt = action.at();
        count(Counter.HOLDS_CAPTURED);
        // The capture posts a transfer and then transitions the hold, and CaptureHold records both.
        transferPosted(entryRef, action.at(), action.date(), hold.accountRef, action.counterpartyRef(), amount);
        audit(action.at(), AuditAction.HOLD_CAPTURED, reference,
                Map.of("status", "PLACED"),
                Map.of(
                        "status", "CAPTURED",
                        "accountRef", hold.accountRef,
                        "capturedByTransferRef", entryRef,
                        "capturedAmountMinor", String.valueOf(amount.amountMinor()),
                        "transitionedAt", action.at().toString()));
    }

    /** Releases the oldest open hold on the account. No posting: nothing moved. */
    private void releaseHold(DrawnAction action) {
        String reference = takeOpenHold(action.accountRef());
        if (reference == null) {
            count(Counter.HOLD_NOT_FOUND);
            return;
        }
        HoldState hold = holds.get(reference);
        hold.status = "RELEASED";
        hold.transitionedAt = action.at();
        count(Counter.HOLDS_RELEASED);
        audit(action.at(), AuditAction.HOLD_RELEASED, reference,
                Map.of("status", "PLACED"),
                Map.of(
                        "status", "RELEASED",
                        "accountRef", hold.accountRef,
                        "transitionedAt", action.at().toString()));
    }

    private String takeOpenHold(String accountRef) {
        Deque<String> open = openHolds.get(accountRef);
        return open == null ? null : open.pollFirst();
    }

    /** Writes the materialised balances and closes the load. Every account gets a row. */
    public LoadSummary finish() {
        requireHeader();
        sink.checkpoint(currentDate);

        for (Map.Entry<String, HoldState> entry : holds.entrySet()) {
            HoldState hold = entry.getValue();
            sink.hold(new HoldRow(
                    entry.getKey(),
                    hold.accountRef,
                    hold.amountMinor,
                    baseCurrency.code(),
                    hold.status,
                    hold.placedAt,
                    null,
                    hold.capturedBy,
                    hold.transitionedAt));
        }

        Instant at = header.to().atStartOfDay(ZoneOffset.UTC).toInstant();
        for (Map.Entry<String, AccountState> entry : accounts.entrySet()) {
            sink.balance(new BalanceRow(entry.getKey(), entry.getValue().bookedMinor, baseCurrency.code(), at));
        }
        sink.checkpoint(header.to());
        return new LoadSummary(header, Map.copyOf(counters), busiestAccount(), chain.length(), chain.head());
    }

    /**
     * The customer account the load gave the most postings to.
     *
     * <p>Reported rather than chosen: the deep-cursor query plan is captured against whatever the
     * draw actually produced, and an account planted to be deep would be a plan of the fixture this
     * package exists to replace.
     *
     * <p><strong>The treasury is excluded, and it is the answer if it is not.</strong> Every account
     * in the estate is funded by debiting it, so it carries one leg per account and wins by a factor
     * of thousands - and a statement page captured against it would be a plan of the opening phase
     * rather than of a customer's year. Found by running the first load and reading the manifest.
     */
    private LoadSummary.Busiest busiestAccount() {
        LoadSummary.Busiest busiest = LoadSummary.Busiest.none();
        for (Map.Entry<String, AccountState> entry : accounts.entrySet()) {
            if (entry.getKey().equals(header.treasuryAccountRef())) {
                continue;
            }
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
