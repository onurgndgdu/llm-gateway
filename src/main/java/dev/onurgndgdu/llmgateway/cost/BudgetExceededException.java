package dev.onurgndgdu.llmgateway.cost;

import java.math.BigDecimal;

/** The caller has spent its daily allowance. */
public class BudgetExceededException extends RuntimeException {

    public BudgetExceededException(String callerId, BigDecimal spent, BigDecimal limit) {
        super("caller '%s' has spent %s of a %s daily budget".formatted(callerId, spent, limit));
    }
}
