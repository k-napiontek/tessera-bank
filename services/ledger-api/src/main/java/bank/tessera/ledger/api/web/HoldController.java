package bank.tessera.ledger.api.web;

import bank.tessera.ledger.api.dto.CaptureRequestDto;
import bank.tessera.ledger.api.dto.HoldDto;
import bank.tessera.ledger.api.dto.HoldRequestDto;
import bank.tessera.ledger.api.dto.TransferDto;
import bank.tessera.ledger.application.CaptureHold;
import bank.tessera.ledger.application.PlaceHold;
import bank.tessera.ledger.application.ReleaseHold;
import bank.tessera.ledger.domain.HoldRef;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Holds: placing, capturing and releasing.
 *
 * <p>Capturing returns a {@code Transfer} rather than a {@code Hold}, because that is what a capture
 * produces: the hold is cleared and money moves, in one transaction, so available balance is never
 * reduced twice.
 *
 * <p>Releasing carries an {@code Idempotency-Key} and no body at all. Its fingerprint is therefore
 * the method and the path with an empty body, which is enough - the operation names exactly one hold
 * and there is nothing else about it a client could vary.
 */
@RestController
@RequestMapping("/v1")
@Validated
public class HoldController {

    private final PlaceHold placeHold;
    private final CaptureHold captureHold;
    private final ReleaseHold releaseHold;

    public HoldController(PlaceHold placeHold, CaptureHold captureHold, ReleaseHold releaseHold) {
        this.placeHold = placeHold;
        this.captureHold = captureHold;
        this.releaseHold = releaseHold;
    }

    @PostMapping("/accounts/{accountRef}/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldDto place(
            @PathVariable String accountRef,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idempotencyKey,
            @Valid @RequestBody HoldRequestDto request) {
        return HoldDto.from(placeHold.execute(request.toCommand(accountRef)));
    }

    @PostMapping("/holds/{holdRef}/capture")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferDto capture(
            @PathVariable String holdRef,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idempotencyKey,
            @Valid @RequestBody CaptureRequestDto request) {
        return TransferDto.from(captureHold.execute(request.toCommand(holdRef)));
    }

    @PostMapping("/holds/{holdRef}/release")
    public HoldDto release(
            @PathVariable String holdRef,
            @RequestHeader("Idempotency-Key") @Size(min = 16, max = 64) String idempotencyKey) {
        return HoldDto.from(releaseHold.execute(HoldRef.of(holdRef)));
    }
}
