package bank.tessera.ledger.loader;

/**
 * What a load counted.
 *
 * <p>Two of these are the reason the enum exists at all. {@code CURRENCY_SUBSTITUTED} and
 * {@code REFUSED_INSUFFICIENT_FUNDS} are places where the model asks for something this estate cannot
 * take, and a loader that quietly did something else instead would produce a dataset nobody could
 * defend. WP-21 made the same choice for the same reason - see follow-up F-72 - and the figures land
 * in the load manifest rather than in a log line nobody reads.
 */
public enum Counter {

    /** Accounts opened, including the treasury. */
    ACCOUNTS_OPENED,

    /** Opening balances posted from the treasury. */
    FUNDING_ENTRIES,

    TRANSFERS_POSTED,
    TRANSFERS_REVERSED,
    HOLDS_PLACED,
    HOLDS_CAPTURED,
    HOLDS_RELEASED,

    /** Journal entries written, of every kind. */
    ENTRIES,

    /** Postings written. Two per entry, and the figure a query plan is read against. */
    POSTINGS,

    /** Audit rows written. One per account opened and one per entry or hold transition. */
    AUDIT_ROWS,

    /**
     * Actions that ask a question rather than move money. A balance enquiry writes no row, which is
     * exactly why WP-21's first run reported 2 525 replays: a read answers like everything else.
     */
    READS_IGNORED,

    /**
     * Transfers drawn in one currency and posted in another.
     *
     * <p>The ledger fixes an account's currency when it is opened and requires a transfer to be in
     * the currency of both sides. The model draws a currency per transfer from a mix of up to five.
     * The estate is opened in one, so the rest are substituted and counted here. F-72.
     */
    CURRENCY_SUBSTITUTED,

    /**
     * Transfers the ledger would have refused, and which are therefore not written.
     *
     * <p>Nothing in the schema stops a negative balance - {@code OverdraftPolicy} is the domain's, and
     * the bulk path does not go through it - so this counter is the only thing between the dataset and
     * a ledger full of rows {@code Transfer} would have rejected.
     */
    REFUSED_INSUFFICIENT_FUNDS,

    /** Hold operations naming a hold that does not exist yet, which a driver would have 404'd. */
    HOLD_NOT_FOUND,

    /**
     * Reversals drawn against an account that has nothing left to reverse.
     *
     * <p>The population draws a fresh reference for a reversal rather than naming an entry, so the
     * loader reverses the account's most recent unreversed transfer. Early in a load, and on an
     * account that has only received, there is none - and inventing one would be reversing a payment
     * that never happened.
     */
    NOTHING_TO_REVERSE
}
