package bank.tessera.ledger.domain;

/**
 * The five account types of double-entry bookkeeping, each with its normal balance.
 *
 * <p>A customer's current account is a <strong>liability</strong> of the bank - the bank owes the
 * customer that money. Cash and central-bank reserves are <strong>assets</strong>. This is the
 * distinction that separates a real ledger from a {@code balance} column, and getting it backwards
 * produces a system that appears to work right up until the balance sheet is drawn.
 *
 * <p>Traces to {@code Account.accountType} in docs/architecture/canonical-data-model.md.
 */
public enum AccountType {

    /** What the bank owns: cash, reserves, loans it has made. Increases on the debit side. */
    ASSET(Direction.DEBIT),

    /** What the bank owes: customer deposits. Increases on the credit side. */
    LIABILITY(Direction.CREDIT),

    /** The bank's own capital. Increases on the credit side. */
    EQUITY(Direction.CREDIT),

    /** Income earned. Increases on the credit side. */
    REVENUE(Direction.CREDIT),

    /** Costs incurred. Increases on the debit side. */
    EXPENSE(Direction.DEBIT);

    private final Direction normalBalance;

    AccountType(Direction normalBalance) {
        this.normalBalance = normalBalance;
    }

    /** The side on which this type of account increases. */
    public Direction normalBalance() {
        return normalBalance;
    }

    /**
     * The signed effect of posting {@code amount} in {@code direction} to an account of this type.
     *
     * <p>Positive when the posting increases the account, negative when it reduces it. The rule is
     * explicit here and nowhere else, so there is exactly one place to check when a balance looks
     * wrong.
     *
     * @param direction the side of the posting
     * @param amount a positive amount; direction, not sign, says which way money moved
     */
    public Money signedEffect(Direction direction, Money amount) {
        return direction == normalBalance ? amount : amount.negate();
    }
}
