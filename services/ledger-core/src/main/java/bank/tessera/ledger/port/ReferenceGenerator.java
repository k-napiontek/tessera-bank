package bank.tessera.ledger.port;

import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.HoldRef;

/**
 * Allocates the estate-wide references the ledger assigns.
 *
 * <p>Both forms are {@code CCYYMMDD} plus a ten-digit sequence, per the canonical data model. The
 * sequence is the adapter's problem: it must be unique and it must not require a table scan to
 * advance, and a use case that generated one for itself would be a second implementation of a
 * format four tiers depend on.
 */
public interface ReferenceGenerator {

    /** The next journal entry reference. The rest of the estate calls this value {@code transferRef}. */
    EntryRef nextEntryReference();

    HoldRef nextHoldReference();
}
