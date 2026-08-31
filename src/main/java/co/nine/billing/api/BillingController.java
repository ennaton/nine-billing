package co.nine.billing.api;

import co.nine.billing.application.LedgerService;
import co.nine.billing.auth.Tenancy;
import co.nine.billing.domain.Currencies;
import co.nine.billing.domain.Money;
import co.nine.billing.metering.Charge;
import co.nine.billing.metering.MeteringRepository;
import co.nine.billing.metering.MeteringService;
import co.nine.billing.metering.UsageEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class BillingController {

    private final MeteringService metering;
    private final LedgerService ledger;

    public BillingController(MeteringService metering, LedgerService ledger) {
        this.metering = metering;
        this.ledger = ledger;
    }

    // ---- requests / responses -------------------------------------------

    public record UsageRequest(
            @NotBlank String eventId,
            @NotNull UUID tenantId,
            @NotBlank String metric,
            // The ceiling is not a round number picked for comfort. The event
            // contract caps duration_ms at 86,400,000, so the largest quantity any
            // metric can legitimately carry is a 24 hour run expressed in seconds,
            // 86,400. A million is an order of magnitude above that, which leaves
            // room for a metric nobody has thought of yet, and ten orders of
            // magnitude below the point where the price multiplication overflows.
            //
            // Without it, Math.multiplyExact in PricePlan throws ArithmeticException
            // for a request that is simply out of range, and the caller is told 500.
            // A 500 says retry; this will never succeed. It is a 400.
            @Min(1) @Max(1_000_000) long quantity,
            Instant occurredAt) {}

    public record ChargeResponse(UUID transactionId, long chargedMinor, String currency, boolean replayed) {
        static ChargeResponse from(Charge c) {
            return new ChargeResponse(c.transactionId(), c.amount().minor(), c.amount().currency().getCurrencyCode(), c.replayed());
        }
    }

    public record BalanceResponse(UUID tenantId, long owedMinor, String currency, String display) {}

    public record ReverseRequest(@NotNull UUID tenantId, @NotBlank String idempotencyKey, @NotBlank String reason) {}

    public record ReverseResponse(UUID reversalTransactionId, UUID reversedTransactionId) {}

    // ---- endpoints ------------------------------------------------------

    /** Report usage. Same eventId twice returns the same charge with replayed=true and a 200 instead of 201. */
    @PostMapping("/usage")
    public ResponseEntity<ChargeResponse> usage(@Valid @RequestBody UsageRequest r) {
        Tenancy.requireOwn(r.tenantId());
        Instant at = r.occurredAt() != null ? r.occurredAt() : Instant.now();
        Charge c = metering.charge(new UsageEvent(r.eventId(), r.tenantId(), r.metric(), r.quantity(), at));
        return ResponseEntity.status(c.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(ChargeResponse.from(c));
    }

    /** What the tenant currently owes. */
    @GetMapping("/tenants/{tenantId}/balance")
    public BalanceResponse balance(@PathVariable UUID tenantId,
                                   @RequestParam(defaultValue = "GBP") String currency) {
        Tenancy.requireOwn(tenantId);
        Money owed = metering.owed(tenantId, Currencies.require(currency));
        return new BalanceResponse(tenantId, owed.minor(), currency, display(owed));
    }

    /** Recent ledger lines for a tenant, newest first. */
    @GetMapping("/tenants/{tenantId}/ledger")
    public List<MeteringRepository.LedgerLine> ledger(@PathVariable UUID tenantId,
                                                      @RequestParam(defaultValue = "50") @Min(1) int limit) {
        Tenancy.requireOwn(tenantId);
        return metering.recent(tenantId, Math.min(limit, 500));
    }

    /** Reverse a transaction. The reversal is a new transaction; nothing is deleted. */
    @PostMapping("/ledger/{transactionId}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    public ReverseResponse reverse(@PathVariable UUID transactionId, @Valid @RequestBody ReverseRequest r) {
        Tenancy.requireOwn(r.tenantId());
        UUID reversal = ledger.reverse(r.tenantId(), transactionId, r.idempotencyKey(), r.reason());
        return new ReverseResponse(reversal, transactionId);
    }

    /**
     * Formats minor units for display.
     *
     * <p>Not every currency has a minor unit. JPY, KWD and BHD report zero
     * fraction digits, and the obvious format string becomes "%d.%00d", where
     * a width of zero is not a legal format specifier. The previous version of
     * this method threw on any balance denominated in one of them, reachable
     * from ?currency=JPY. A currency with no decimals gets no decimal point.
     *
     * <p>Currencies with more than two digits are handled by the same path
     * rather than assuming two: JOD and TND have three.
     */
    private static String display(Money m) {
        int digits = m.currency().getDefaultFractionDigits();
        if (digits <= 0) {
            return m.minor() + " " + m.currency();
        }
        long scale = 1;
        for (int i = 0; i < digits; i++) scale *= 10;
        long units = m.minor() / scale;
        long fraction = Math.abs(m.minor() % scale);
        // A negative balance between zero and one minor unit still needs its
        // sign: -0.40 must not print as 0.40.
        String sign = (m.minor() < 0 && units == 0) ? "-" : "";
        return String.format("%s%d.%0" + digits + "d %s", sign, units, fraction, m.currency());
    }
}
