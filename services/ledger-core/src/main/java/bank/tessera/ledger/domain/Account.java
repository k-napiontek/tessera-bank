package bank.tessera.ledger.domain;

import java.util.Objects;

/**
 * An account in the ledger: what it is, whose it is, and what may be posted to it.
 *
 * <p><strong>It carries no balance.</strong> A balance is derived from postings, and storing one
 * here would create a second source of truth on day one - the exact defect {@code batch/recon}
 * exists to detect between the mainframe and this ledger. {@link Balance} is computed, never stored
 * on the aggregate.
 *
 * <p>Immutable: every change returns a new instance.
 *
 * <p>Traces to {@code Account} in docs/architecture/canonical-data-model.md.
 */
public final class Account {

    private final AccountRef reference;
    private final CustomerRef customer;
    private final AccountType type;
    private final CurrencyCode currency;
    private final AccountStatus status;
    private final OverdraftPolicy overdraft;

    private Account(Builder builder) {
        this.reference = Objects.requireNonNull(builder.reference, "reference");
        this.customer = Objects.requireNonNull(builder.customer, "customer");
        this.type = Objects.requireNonNull(builder.type, "type");
        this.currency = Objects.requireNonNull(builder.currency, "currency");
        this.status = Objects.requireNonNull(builder.status, "status");
        this.overdraft = Objects.requireNonNull(builder.overdraft, "overdraft");
    }

    public static Builder builder() {
        return new Builder();
    }

    public AccountRef reference() {
        return reference;
    }

    public CustomerRef customer() {
        return customer;
    }

    public AccountType type() {
        return type;
    }

    /** An account holds exactly one currency, for life. */
    public CurrencyCode currency() {
        return currency;
    }

    public AccountStatus status() {
        return status;
    }

    public OverdraftPolicy overdraft() {
        return overdraft;
    }

    public boolean canBePosted() {
        return status.allowsPosting();
    }

    public Account withStatus(AccountStatus newStatus) {
        return builder()
                .reference(reference)
                .customer(customer)
                .type(type)
                .currency(currency)
                .status(Objects.requireNonNull(newStatus, "status"))
                .overdraft(overdraft)
                .build();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Account that && reference.equals(that.reference);
    }

    @Override
    public int hashCode() {
        return reference.hashCode();
    }

    @Override
    public String toString() {
        return "Account[" + reference + " " + type + " " + currency + " " + status + "]";
    }

    /** Builder, because six mandatory constructor arguments of similar shape invite silent swaps. */
    public static final class Builder {

        private AccountRef reference;
        private CustomerRef customer;
        private AccountType type;
        private CurrencyCode currency;
        private AccountStatus status;
        private OverdraftPolicy overdraft;

        private Builder() {}

        public Builder reference(AccountRef value) {
            this.reference = value;
            return this;
        }

        public Builder customer(CustomerRef value) {
            this.customer = value;
            return this;
        }

        public Builder type(AccountType value) {
            this.type = value;
            return this;
        }

        public Builder currency(CurrencyCode value) {
            this.currency = value;
            return this;
        }

        public Builder status(AccountStatus value) {
            this.status = value;
            return this;
        }

        public Builder overdraft(OverdraftPolicy value) {
            this.overdraft = value;
            return this;
        }

        public Account build() {
            return new Account(this);
        }
    }
}
