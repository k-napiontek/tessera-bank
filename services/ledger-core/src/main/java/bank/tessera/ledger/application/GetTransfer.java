package bank.tessera.ledger.application;

import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.JournalEntry;
import bank.tessera.ledger.port.JournalEntryRepository;
import bank.tessera.ledger.port.LedgerReadModel;
import bank.tessera.ledger.port.UnitOfWork;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Fetches a transfer: its journal entry, when it posted, and whether anything has reversed it. */
public final class GetTransfer {

    private final JournalEntryRepository entries;
    private final LedgerReadModel readModel;
    private final UnitOfWork unitOfWork;

    public GetTransfer(
            JournalEntryRepository entries, LedgerReadModel readModel, UnitOfWork unitOfWork) {
        this.entries = Objects.requireNonNull(entries, "entries");
        this.readModel = Objects.requireNonNull(readModel, "readModel");
        this.unitOfWork = Objects.requireNonNull(unitOfWork, "unitOfWork");
    }

    public Optional<TransferView> byReference(EntryRef reference) {
        Objects.requireNonNull(reference, "reference");
        return unitOfWork.inTransaction(() -> {
            Optional<JournalEntry> found = entries.findByReference(reference);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            Instant postedAt = readModel
                    .entryPostedAt(reference)
                    .orElseThrow(() -> new IllegalStateException(
                            "Entry " + reference + " exists but has no posting instant."));
            return Optional.of(
                    TransferView.of(found.get(), postedAt, readModel.reversedBy(reference).orElse(null)));
        });
    }
}
