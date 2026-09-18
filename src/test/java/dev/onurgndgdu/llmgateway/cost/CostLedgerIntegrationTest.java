package dev.onurgndgdu.llmgateway.cost;

import static org.assertj.core.api.Assertions.assertThat;

import dev.onurgndgdu.llmgateway.TestcontainersConfiguration;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * The ledger against a real Redis.
 *
 * <p>Run against a container rather than an in-memory fake, because the
 * behaviour being relied on here — atomic increment under concurrency and
 * expiry semantics — is exactly what a fake tends to approximate.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CostLedgerIntegrationTest {

    /**
     * Pinned so the test cannot straddle a day boundary when it runs near
     * midnight. The ledger is built by hand rather than injected: what is under
     * test is its behaviour against Redis, not how Spring wires it together.
     */
    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-05-01T12:00:00Z"), ZoneOffset.UTC);

    private CostLedger ledger;

    @Autowired private ReactiveStringRedisTemplate redis;

    @BeforeEach
    void setUp() {
        ledger = new CostLedger(redis, FIXED);
    }

    @Test
    void startsAtZeroForAnUnknownCaller() {
        StepVerifier.create(ledger.spentToday("caller-unknown"))
                .assertNext(spent -> assertThat(spent).isEqualByComparingTo("0"))
                .verifyComplete();
    }

    @Test
    void accumulatesSpendAcrossCalls() {
        String caller = "caller-accumulate";

        ledger.record(caller, new BigDecimal("0.25")).block();
        ledger.record(caller, new BigDecimal("0.50")).block();

        assertThat(ledger.spentToday(caller).block()).isEqualByComparingTo("0.75");
    }

    @Test
    void concurrentIncrementsAreNotLost() {
        String caller = "caller-concurrent";
        BigDecimal each = new BigDecimal("0.01");

        // Read-modify-write would drop increments here; an atomic counter does not.
        Flux.range(0, 200)
                .flatMap(i -> ledger.record(caller, each), 32)
                .blockLast();

        assertThat(ledger.spentToday(caller)).isNotNull();
        assertThat(ledger.spentToday(caller).block()).isEqualByComparingTo("2.00");
    }

    @Test
    void amountsTooSmallToRepresentAreNotCharged() {
        String caller = "caller-dust";

        // Below one micro-unit. Rounding these up would overcharge on every
        // call; the ledger drops them instead.
        IntStream.range(0, 5)
                .forEach(i -> ledger.record(caller, new BigDecimal("0.0000001")).block());

        assertThat(ledger.spentToday(caller).block()).isEqualByComparingTo("0");
    }
}
