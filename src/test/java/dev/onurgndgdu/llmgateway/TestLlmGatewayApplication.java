package dev.onurgndgdu.llmgateway;

import org.springframework.boot.SpringApplication;

public class TestLlmGatewayApplication {

	public static void main(String[] args) {
		SpringApplication.from(LlmGatewayApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
