package bank.tessera.ledger.api.web;

import bank.tessera.ledger.api.dto.AccountDto;
import bank.tessera.ledger.api.dto.OpenAccountRequestDto;
import bank.tessera.ledger.api.dto.BalanceDto;
import bank.tessera.ledger.api.dto.HoldDto;
import bank.tessera.ledger.api.dto.StatementDto;
import bank.tessera.ledger.application.AccountNotFoundException;
import bank.tessera.ledger.application.GetAccount;
import bank.tessera.ledger.application.GetBalance;
import bank.tessera.ledger.application.GetStatement;
import bank.tessera.ledger.application.ListHolds;
import bank.tessera.ledger.application.OpenAccount;
import bank.tessera.ledger.domain.AccountRef;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The accounts resource: opening an account, and reading its metadata, balances, statements and
 * holds.
 *
 * <p>Every method is a thin translation. The controller resolves a path variable into a domain
 * reference, calls one use case, and maps the answer - it makes no decision of its own, because a
 * decision made here would be one the domain tests cannot reach.
 *
 * <p>An unknown reference throws {@link AccountNotFoundException} rather than returning an empty
 * document, so that "no such account" and "an account with nothing in it" stay different answers all
 * the way to the client.
 */
@RestController
@RequestMapping("/v1")
public class AccountController {

    private final OpenAccount openAccount;
    private final GetAccount getAccount;
    private final GetBalance getBalance;
    private final GetStatement getStatement;
    private final ListHolds listHolds;
    private final Clock clock;

    public AccountController(
            OpenAccount openAccount,
            GetAccount getAccount,
            GetBalance getBalance,
            GetStatement getStatement,
            ListHolds listHolds,
            Clock clock) {
        this.openAccount = openAccount;
        this.getAccount = getAccount;
        this.getBalance = getBalance;
        this.getStatement = getStatement;
        this.listHolds = listHolds;
        this.clock = clock;
    }

    @PostMapping("/accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public AccountDto open(@Valid @RequestBody OpenAccountRequestDto request) {
        return AccountDto.from(openAccount.open(request.toCommand()));
    }

    @GetMapping("/accounts/{accountRef}")
    public AccountDto account(@PathVariable String accountRef) {
        AccountRef reference = AccountRef.of(accountRef);
        return getAccount.byReference(reference)
                .map(AccountDto::from)
                .orElseThrow(() -> new AccountNotFoundException(reference));
    }

    @GetMapping("/accounts/{accountRef}/balance")
    public BalanceDto balance(@PathVariable String accountRef) {
        AccountRef reference = AccountRef.of(accountRef);
        return getBalance.of(reference)
                .map(balance -> BalanceDto.from(balance, clock.instant()))
                .orElseThrow(() -> new AccountNotFoundException(reference));
    }

    @GetMapping("/accounts/{accountRef}/statement")
    public StatementDto statement(
            @PathVariable String accountRef,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "100") int limit) {
        AccountRef reference = AccountRef.of(accountRef);
        return getStatement.of(reference, from, to, cursor, limit)
                .map(StatementDto::from)
                .orElseThrow(() -> new AccountNotFoundException(reference));
    }

    @GetMapping("/accounts/{accountRef}/holds")
    public List<HoldDto> holds(
            @PathVariable String accountRef,
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        AccountRef reference = AccountRef.of(accountRef);
        return listHolds.on(reference, includeInactive)
                .map(holds -> holds.stream().map(HoldDto::from).toList())
                .orElseThrow(() -> new AccountNotFoundException(reference));
    }
}
