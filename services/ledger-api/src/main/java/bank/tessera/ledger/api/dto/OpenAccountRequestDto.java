package bank.tessera.ledger.api.dto;

import bank.tessera.ledger.application.OpenAccount;
import bank.tessera.ledger.domain.AccountRef;
import bank.tessera.ledger.domain.AccountType;
import bank.tessera.ledger.domain.CurrencyCode;
import bank.tessera.ledger.domain.CustomerRef;
import bank.tessera.ledger.domain.OverdraftPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;

/**
 * Opening an account at a reference the caller supplies.
 *
 * <p>The ledger allocates no account numbers: {@code customer-master} owns onboarding and the
 * numbering series. That is what makes the operation idempotent without an {@code Idempotency-Key} -
 * a retry names the same reference, and opening one that already exists is a {@code 409}.
 */
public record OpenAccountRequestDto(
        @NotBlank @Pattern(regexp = "^TB[0-9A-Z]{14}$") String accountRef,
        @NotBlank @Pattern(regexp = "^CU[0-9]{10}$") String customerRef,
        @NotNull
                @Pattern(regexp = "ASSET|LIABILITY|EQUITY|REVENUE|EXPENSE")
                String accountType,
        @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency,
        LocalDate openedDate,
        @Valid MoneyDto overdraftLimit) {

    public OpenAccount.Command toCommand() {
        // Absent forbids an overdraft outright, which is not the same as a limit of zero: a zero
        // limit is an arranged facility that happens to be exhausted, and the two take different
        // branches in every balance check downstream.
        OverdraftPolicy policy = overdraftLimit == null
                ? OverdraftPolicy.forbidden()
                : OverdraftPolicy.upTo(overdraftLimit.toDomain());

        return new OpenAccount.Command(
                AccountRef.of(accountRef),
                CustomerRef.of(customerRef),
                AccountType.valueOf(accountType),
                CurrencyCode.of(currency),
                openedDate,
                policy);
    }
}
