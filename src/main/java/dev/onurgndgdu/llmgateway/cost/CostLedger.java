package dev.onurgndgdu.llmgateway.cost;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Running spend per caller, per day.
 *
 * <p>Redis rather than a database: these counters are written on every single
 * request and read before every single request, they are shared across
 * instances, and they are worth nothing after the billing period closes.
 * Durability would cost throughput and buy little — the authoritative record
 * of spend is the vendor's invoice, not this.
 *
 * <p>Amounts are stored as micro-units of currency in an integer counter, so
 * concurrent increments are atomic in Redis. Storing a decimal string would
 * force a read-modify-write and lose increments under load.
 */
@Component
public class CostLedger {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final BigDecimal MICROS = new BigDecimal("1000000");
    private static final Duration RETENTION = Duration.ofDays(35);

    private final ReactiveStringRedisTemplate redis;
    private final Clock clock;

    public CostLedger(ReactiveStringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    public Mono<Void> record(String callerId, BigDecimal amount) {
        long micros = amount.multiply(MICROS).longValue();
        if (micros == 0) {
            return Mono.empty();
        }

        String key = keyFor(callerId);
        return redis
                .opsForValue()
                .increment(key, micros)
                // Expiry is set on every write rather than only on creation: a
                // TTL that is set once can be lost by a later overwrite, and a
                // counter without one accumulates forever.
                .flatMap(updated -> redis.expire(key, RETENTION))
                .then();
    }

    public Mono<BigDecimal> spentToday(String callerId) {
        return redis
                .opsForValue()
                .get(keyFor(callerId))
                .map(value -> new BigDecimal(value).divide(MICROS))
                .defaultIfEmpty(BigDecimal.ZERO);
    }

    private String keyFor(String callerId) {
        return "cost:%s:%s".formatted(callerId, LocalDate.now(clock).format(DAY));
    }
}
