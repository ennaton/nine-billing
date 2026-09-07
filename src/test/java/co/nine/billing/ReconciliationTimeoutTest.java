package co.nine.billing;

import co.nine.billing.reconciliation.ReconciliationRepository;
import co.nine.billing.reconciliation.ReconciliationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * The timeout is a Duration and Spring reads a unitless number as milliseconds,
 * so NINE_RECONCILE_TIMEOUT=120 binds as 120 ms and truncates to zero seconds.
 * Measured with that value against a healthy database: every statement fails
 * before it is issued, the failure is not recorded because recording runs
 * through the same template, and health reads the missing row as clean. A value
 * that cannot work has to stop the service at startup instead.
 */
class ReconciliationTimeoutTest {

    ReconciliationService withTimeout(Duration timeout) {
        return new ReconciliationService(mock(ReconciliationRepository.class),
            mock(PlatformTransactionManager.class), timeout);
    }

    @Test
    @DisplayName("a timeout under one second is refused instead of disabling reconciliation")
    void underOneSecondIsRefused() {
        assertThatThrownBy(() -> withTimeout(Duration.ofMillis(120)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("nine.billing.reconcile.timeout");
    }

    @Test
    @DisplayName("a timeout the transaction API cannot hold is refused instead of wrapping")
    void anOverflowingTimeoutIsRefused() {
        // 1193047 hours is 4294969200 seconds, and the cast to int lands on
        // 1904: a 136 year timeout would quietly become 31 minutes.
        assertThatThrownBy(() -> withTimeout(Duration.ofHours(1193047)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the shipped value is accepted")
    void theShippedValueIsAccepted() {
        assertThatCode(() -> withTimeout(Duration.ofMinutes(2))).doesNotThrowAnyException();
    }
}
