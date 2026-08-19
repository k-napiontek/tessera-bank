package bank.tessera.ledger.api.dto;

import bank.tessera.ledger.application.StatementView;
import java.time.LocalDate;
import java.util.List;

/**
 * One page of a statement.
 *
 * <p>{@code openingBalance} plus every movement on this page equals {@code closingBalance}, and this
 * page's {@code closingBalance} is the next page's {@code openingBalance}. Follow {@code nextCursor}
 * until it is null; it is opaque, and a client that parses one has coupled itself to a sort key that
 * is free to change.
 */
public record StatementDto(
        String accountRef,
        LocalDate from,
        LocalDate to,
        MoneyDto openingBalance,
        MoneyDto closingBalance,
        List<MovementDto> movements,
        String nextCursor) {

    public static StatementDto from(StatementView view) {
        List<MovementDto> movements =
                view.movements().stream().map(MovementDto::from).toList();
        return new StatementDto(
                view.account().value(),
                view.from(),
                view.to(),
                MoneyDto.from(view.openingBalance()),
                MoneyDto.from(view.closingBalance()),
                movements,
                view.nextCursor().orElse(null));
    }
}
