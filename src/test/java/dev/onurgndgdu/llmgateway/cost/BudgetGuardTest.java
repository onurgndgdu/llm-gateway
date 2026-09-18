package dev.onurgndgdu.llmgateway.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.onurgndgdu.llmgateway.config.GatewayProperties;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class BudgetGuardTest {

    private BudgetGuard guardWith(GatewayProperties.Budgets budgets, String spent) {
        CostLedger ledger = mock(CostLedger.class);
        when(ledger.spentToday(anyString())).thenReturn(Mono.just(new BigDecimal(spent)));
        return new BudgetGuard(ledger, new GatewayProperties(Map.of(), null, Map.of(), budgets));
    }

    @Test
    void allowsACallerUnderItsLimit() {
        var guard =
                guardWith(new GatewayProperties.Budgets(new BigDecimal("10.00"), Map.of()), "9.99");

        StepVerifier.create(guard.check("caller")).verifyComplete();
    }

    @Test
    void rejectsACallerThatHasReachedItsLimit() {
        var guard =
                guardWith(new GatewayProperties.Budgets(new BigDecimal("10.00"), Map.of()), "10.00");

        StepVerifier.create(guard.check("caller"))
                .expectError(BudgetExceededException.class)
                .verify();
    }

    @Test
    void aPerCallerLimitOverridesTheDefault() {
        var guard =
                guardWith(
                        new GatewayProperties.Budgets(
                                new BigDecimal("10.00"), Map.of("vip", new BigDecimal("100.00"))),
                        "50.00");

        StepVerifier.create(guard.check("vip")).verifyComplete();
        StepVerifier.create(guard.check("ordinary"))
                .expectError(BudgetExceededException.class)
                .verify();
    }

    @Test
    void noConfiguredBudgetLeavesTheCallerUncapped() {
        // A gateway that starts rejecting traffic the moment it is deployed,
        // because nobody set a budget yet, is worse than one that does not cap.
        var guard = guardWith(new GatewayProperties.Budgets(null, Map.of()), "9999.00");

        StepVerifier.create(guard.check("caller")).verifyComplete();
    }

    @Test
    void theLedgerIsNotEvenConsultedWhenThereIsNoLimit() {
        CostLedger ledger = mock(CostLedger.class);
        var guard =
                new BudgetGuard(
                        ledger,
                        new GatewayProperties(
                                Map.of(), null, Map.of(), new GatewayProperties.Budgets(null, Map.of())));

        StepVerifier.create(guard.check("caller")).verifyComplete();

        // An uncapped caller should not pay a Redis round trip per request.
        org.mockito.Mockito.verifyNoInteractions(ledger);
        assertThat(true).isTrue();
    }
}
