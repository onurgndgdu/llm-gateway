package dev.onurgndgdu.llmgateway.config;

import dev.onurgndgdu.llmgateway.provider.LlmProvider;
import dev.onurgndgdu.llmgateway.provider.mock.MockProvider;
import dev.onurgndgdu.llmgateway.provider.mock.MockScenario;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the mock provider so the gateway can be run and exercised without
 * any vendor credentials.
 *
 * <p>Enabled by default while no real adapter exists. Once real providers land
 * this becomes opt-in, and it is never meant to be enabled in production.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "gateway.mock.enabled", havingValue = "true", matchIfMissing = true)
class MockProviderConfiguration {

    @Bean
    LlmProvider mockProvider() {
        return new MockProvider("mock")
                .defaultScenario(MockScenario.replying("This is a mock completion."));
    }
}
