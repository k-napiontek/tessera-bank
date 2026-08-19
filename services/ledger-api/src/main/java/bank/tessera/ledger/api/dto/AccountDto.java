package bank.tessera.ledger.api.dto;

import bank.tessera.ledger.application.AccountView;
import java.time.LocalDate;

/** An account and both of its balances, as the contract's {@code Account} schema describes it. */
public record AccountDto(
        String accountRef,
        String customerRef,
        String accountType,
        String currency,
        String status,
        MoneyDto bookedBalance,
        MoneyDto availableBalance,
        LocalDate openedDate,
        LocalDate lastMovementDate) {

    public static AccountDto from(AccountView view) {
        return new AccountDto(
                view.account().reference().value(),
                view.account().customer().value(),
                view.account().type().name(),
                view.account().currency().code(),
                view.account().status().name(),
                MoneyDto.from(view.balance().booked()),
                MoneyDto.from(view.balance().available()),
                view.dates().opened(),
                view.dates().lastMovement().orElse(null));
    }
}
