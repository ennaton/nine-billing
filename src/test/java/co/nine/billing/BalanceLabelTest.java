package co.nine.billing;

import co.nine.billing.api.BillingController;
import co.nine.billing.application.LedgerService;
import co.nine.billing.auth.TenantContext;
import co.nine.billing.domain.Money;
import co.nine.billing.metering.MeteringService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The currency on a balance response names the money that was counted.
 *
 * <p>BI13.4 proved that a tenant holding no JPY book is answered zero rather
 * than handed the GBP figure. That test cannot see this door. The response was
 * built from two sources, the number from the computed {@code Money} and the
 * label from the request string, and they agreed only because {@code owed}
 * happened to build its {@code Money} from the same argument. The day a
 * fallback answers in a different book, the number changes and the label does
 * not, which is the mislabelling BI13.4 exists to close arriving through the
 * one path an endpoint test cannot reach.
 *
 * <p>No Spring context and no database: the point is the wiring inside the
 * controller, and a stub is the only way to produce the disagreement today,
 * because nothing in the service can currently answer in a currency other than
 * the one it was asked for.
 */
class BalanceLabelTest {

    private final UUID tenant = UUID.randomUUID();

    @AfterEach
    void unbind() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("the label follows the money that was counted, not the currency that was asked for")
    void theLabelFollowsTheNumber() {
        MeteringService metering = mock(MeteringService.class);
        // A service that answers in another book. Nothing does this today. It is
        // exactly what a fallback would look like the day someone adds one.
        when(metering.owed(tenant, "USD")).thenReturn(Money.of(1234, "GBP"));

        BillingController controller = new BillingController(metering, mock(LedgerService.class));
        TenantContext.bind(tenant);

        BillingController.BalanceResponse response = controller.balance(tenant, "USD");

        assertThat(response.owedMinor())
            .as("the number is whatever the service counted")
            .isEqualTo(1234);
        assertThat(response.currency())
            .as("so the label has to be the currency that number is in")
            .isEqualTo("GBP");
        assertThat(response.display())
            .as("and the rendered amount too, or the two halves of the answer disagree")
            .isEqualTo("12.34 GBP");
    }
}
