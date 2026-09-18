package dev.onurgndgdu.llmgateway.cost;

import dev.onurgndgdu.llmgateway.config.GatewayProperties;
import java.math.BigDecimal;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Component;

/**
 * Refuses calls from a caller that has spent its daily allowance.
 *
 * <p>The check happens before the call and uses spend recorded so far, which
 * means a caller can overshoot its limit by whatever is in flight when the
 * threshold is crossed. Reserving budget up front would remove that overshoot
 * but requires knowing the cost before the answer exists, and holding a
 * reservation across a call that may stream for a minute. The overshoot is
 * bounded by concurrency and is the cheaper trade.
 */
@Component
public class BudgetGuard {

    private final CostLedger ledger;
    private final GatewayProperties properties;

    public BudgetGuard(CostLedger ledger, GatewayProperties properties) {
        this.ledger = ledger;
        this.properties = properties;
    }

    public Mono<Void> check(String callerId) {
        BigDecimal limit = properties.dailyBudgetFor(callerId);
        if (limit == null) {
            return Mono.empty();
        }

        return ledger
                .spentToday(callerId)
                .flatMap(
                        spent ->
                                spent.compareTo(limit) >= 0
                                        ? Mono.error(new BudgetExceededException(callerId, spent, limit))
                                        : Mono.empty());
    }
}
