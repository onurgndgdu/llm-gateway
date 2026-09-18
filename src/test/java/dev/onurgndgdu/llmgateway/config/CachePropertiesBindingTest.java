package dev.onurgndgdu.llmgateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration binding has a trap that cost a working cache once already: a
 * primitive boolean cannot distinguish an unset property from an explicit
 * false, so setting any single field of a block silently turned the rest off.
 */
class CachePropertiesBindingTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(EnableProperties.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(GatewayProperties.class)
    static class EnableProperties {}

    @Test
    void cacheIsOnWhenNothingIsConfigured() {
        runner.run(
                context ->
                        assertThat(context.getBean(GatewayProperties.class).cache().isEnabled()).isTrue());
    }

    @Test
    void settingAnUnrelatedCacheFieldDoesNotSwitchTheCacheOff() {
        runner
                .withPropertyValues("gateway.cache.ttl=10m")
                .run(
                        context -> {
                            var cache = context.getBean(GatewayProperties.class).cache();
                            assertThat(cache.isEnabled()).isTrue();
                            assertThat(cache.ttl()).hasMinutes(10);
                        });
    }

    @Test
    void enablingSemanticCachingLeavesTheExactCacheOn() {
        runner
                .withPropertyValues("gateway.cache.semantic-enabled=true")
                .run(
                        context -> {
                            var cache = context.getBean(GatewayProperties.class).cache();
                            assertThat(cache.isEnabled()).isTrue();
                            assertThat(cache.isSemanticEnabled()).isTrue();
                        });
    }

    @Test
    void anExplicitFalseIsStillRespected() {
        runner
                .withPropertyValues("gateway.cache.enabled=false")
                .run(
                        context ->
                                assertThat(context.getBean(GatewayProperties.class).cache().isEnabled())
                                        .isFalse());
    }

    @Test
    void semanticCachingIsOffUnlessAskedFor() {
        // It can return an answer to a question nobody asked, so it should
        // never be something a deployment gets by accident.
        runner.run(
                context ->
                        assertThat(context.getBean(GatewayProperties.class).cache().isSemanticEnabled())
                                .isFalse());
    }

    @Test
    void anUnsetSimilarityThresholdFallsBackToTheConservativeDefault() {
        // Zero would match anything at all.
        runner.run(
                context ->
                        assertThat(context.getBean(GatewayProperties.class).cache().similarityThreshold())
                                .isEqualTo(0.95d));
    }
}
