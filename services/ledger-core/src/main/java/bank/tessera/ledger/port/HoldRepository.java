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
}
