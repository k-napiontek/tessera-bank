package bank.tessera.ledger.api.dto;

import bank.tessera.ledger.application.TransferStatus;
import bank.tessera.ledger.application.TransferView;
import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.Posting;
import bank.tessera.ledger.port.Movement;
import java.util.ArrayList;
import java.util.List;

/**
 * A transfer and both of its movements.
 *
 * <p>The estate calls one thing by two names deliberately: a transfer is the customer's intent, a
 * journal entry is its accounting form, and they share a reference so the two views cannot disagree
 * about which is which. This is where the domain's {@code JournalEntry}, {@code EntryRef} and
 * {@code Posting} become the contract's {@code Transfer}, {@code transferRef} and {@code Movement}.
 */
public record TransferDto(
        String transferRef,
        String debitAccountRef,
        String creditAccountRef,
        MoneyDto amount,
        String status,
        String reference,
        String requestedAt,
        String postedAt,
        String reversesTransferRef,
        List<MovementDto> movements) {

    public static TransferDto from(TransferView view) {
        List<Posting> postings = view.entry().postings();
        List<MovementDto> movements = new ArrayList<>(postings.size());
        for (int index = 0; index < postings.size(); index++) {
            Posting posting = postings.get(index);
            // legNo is the posting's position in the entry, which posting.seq preserves in the
            // database. 1 is the debit leg and 2 the credit leg, and the order is deterministic so
            // that a file produced from this is reproducible.
            movements.add(MovementDto.from(Movement.of(
                    view.entry().reference(),
                    index + 1,
                    posting.account(),
                    posting.direction(),
                    posting.amount(),
                    view.entry().valueDate(),
                    view.postedAt(),
                    view.remittanceReference().orElse(null))));
        }

        String postedAt = Timestamps.format(view.postedAt());
        return new TransferDto(
                view.transferReference().value(),
                view.debitAccount().value(),
                view.creditAccount().value(),
                MoneyDto.from(view.amount()),
                view.status().name(),
                view.remittanceReference().orElse(null),
                // The ledger records one instant. requestedAt and postedAt differ only once there is
                // an outbox and a queue in front of the ledger, which is WP-09's work; reporting the
                // same instant for both is honest, and inventing a gap would not be.
                postedAt,
                view.status() == TransferStatus.ACCEPTED ? null : postedAt,
                view.entry().reverses().map(EntryRef::value).orElse(null),
                movements);
    }
}
