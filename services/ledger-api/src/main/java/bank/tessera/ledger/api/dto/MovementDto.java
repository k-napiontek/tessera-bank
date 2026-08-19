package bank.tessera.ledger.api.dto;

import bank.tessera.ledger.port.Movement;
import java.time.LocalDate;

/** One leg of a posting. Immutable once written. */
public record MovementDto(
        String movementRef,
        String transferRef,
        int legNo,
        String accountRef,
        String direction,
        MoneyDto amount,
        LocalDate valueDate,
        String postedAt,
        String reference) {

    public static MovementDto from(Movement movement) {
        return new MovementDto(
                movement.movementReference(),
                movement.entry().value(),
                movement.legNo(),
                movement.account().value(),
                movement.direction().name(),
                MoneyDto.from(movement.amount()),
                movement.valueDate(),
                Timestamps.format(movement.postedAt()),
                movement.reference().orElse(null));
    }
}
