package bank.tessera.ledger.port;

import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.Hold;
import bank.tessera.ledger.domain.HoldRef;
import java.util.List;
import java.util.Optional;

/** Storage of holds. */
public interface HoldRepository {

    Hold save(Hold hold);

    Optional<Hold> findByReference(HoldRef reference);

    /** Holds still reducing available balance on the account. */
    List<Hold> findActiveFor(AccountRef account);

    /**
     * Every hold on the account, whatever its status, oldest first.
     *
     * <p>Separate from {@link #findActiveFor} rather than a flag on it, because the two answer
     * different questions. "What is reserved" drives the available balance and must never include a
     * released hold; "what has happened to this account" is a history, and a caller that wanted the
     * first and was handed the second would overstate what the customer cannot spend.
     */
    List<Hold> findAllFor(AccountRef account);
}
