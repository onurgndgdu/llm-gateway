package dev.onurgndgdu.llmgateway.routing;

/** Where a caller-facing model alias should be sent. */
public record Route(String providerId, String upstreamModel) {}
