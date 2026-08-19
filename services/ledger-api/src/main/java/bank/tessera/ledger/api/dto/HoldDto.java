package bank.tessera.ledger.api.dto;

import bank.tessera.ledger.domain.EntryRef;
import bank.tessera.ledger.domain.Hold;

/** A reservation against available balance. Moves no money until captured. */
public record HoldDto(
        String holdRef,
        String accountRef,
        MoneyDto amount,
        String status,
        String placedAt,
        String expiresAt,
        String capturedByTransferRef,
        String reference) {

    public static HoldDto from(Hold hold) {
        return new HoldDto(
                hold.reference().value(),
                hold.account().value(),
                MoneyDto.from(hold.amount()),
                hold.status().name(),
                Timestamps.format(hold.placedAt()),
                Timestamps.format(hold.expiresAt().orElse(null)),
                hold.capturedBy().map(EntryRef::value).orElse(null),
                null);
    }
}
