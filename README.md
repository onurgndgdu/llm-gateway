# llm-gateway

A gateway that sits in front of LLM providers and gives application teams one
stable endpoint, while the operational concerns — routing, failover, cost,
caching and rate limiting — are handled in one place instead of being copied
into every service that happens to call a model.

> Work in progress. See [docs/ROADMAP.md](docs/ROADMAP.md) for what is built
> and what is next.

## The problem

Calling an LLM provider directly from an application is straightforward until
it reaches production. Then the same questions surface in every service:

- The provider returns 429 or 503. Do we retry? For how long? Do we fall back
  to another model, and who decides that?
- Spend needs to be attributed per team and capped before the invoice arrives,
  not after.
- The same prompt is answered repeatedly. Nothing caches it, because caching
  belongs to no single service.
- Switching or adding a provider means touching every caller.

These are gateway concerns, not application concerns.

## Architecture

```
client ──▶ gateway ──▶ routing ──▶ provider adapter ──▶ upstream provider
               │
               ├── cache (exact match, then semantic)
               ├── rate limiting and quotas   (Redis)
               ├── cost accounting            (Redis)
               └── metrics                    (Micrometer / Prometheus)
```

Providers sit behind a single SPI. The core never knows which vendor is
answering, which is what makes routing and failover possible at all.

## Design decisions

**Reactive, not servlet-based.** The gateway proxies token streams. With
WebFlux, backpressure from a slow client propagates to the upstream provider
instead of being absorbed by an unbounded buffer in the gateway.

**Mock provider before real providers.** The first adapter is a scripted mock
that can inject latency, failures and truncated streams on demand. Resilience
behaviour is what this project is actually about, and it cannot be tested
reliably against a live provider. It also means the full test suite runs
offline, with no API keys.

**Failover is an ordered chain, not a scoring function.** A route lists its
targets in order and the gateway walks them. Health-aware selection is more
sophisticated, but during an incident the question is always "where did this
request actually go", and an ordered list answers it immediately.

**Only retryable conditions fail over.** A malformed request sent to three
vendors in turn is the caller's mistake multiplied by three. Providers classify
their failures; the routing layer decides what to do about them.

**Streams fail over only before the first chunk.** Once bytes are on the wire,
switching provider would splice two different completions into one response and
the caller would have no way to tell. Failing is the honest outcome.

**The gateway measures latency itself.** A provider's own reported figure
excludes the network, any retry and any failover. What matters operationally is
what the caller waited for.

**Unpriced and estimated calls are counted, not hidden.** A model missing from
the price table, or a provider that stopped reporting token counts, would
otherwise quietly turn the spend figures into fiction while every chart still
looked healthy.

**Only deterministic requests are cached.** A non-zero temperature is the
caller asking for variety; serving one stored answer forever removes it
without them being able to tell.

**Semantic caching is off by default.** It can return an answer to a question
nobody asked. The similarity threshold is the whole risk, so it defaults
conservatively, is namespaced per caller, and every hit records the score it
matched on.

**Redis for state.** Counters, quotas and cache entries are short-lived and
shared across instances. A relational store would add durability that none of
this state needs.

## Running

```bash
docker compose up
```

Requires Java 25 and Docker. Integration tests use Testcontainers.

## Stack

Java 25, Spring Boot 4.1, Spring WebFlux, Redis, Resilience4j, Micrometer,
Prometheus, Testcontainers.
