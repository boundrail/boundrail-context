<div align="center">

# boundrail-context

**`boundrail-context` defines what context means, where it may travel, and how deadlines behave across service boundaries.**

Micrometer can move context. `boundrail-context` defines its contract.

[![CI](https://github.com/boundrail/boundrail-context/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/boundrail/boundrail-context/actions/workflows/ci.yml)
![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)
![Spring Boot 4](https://img.shields.io/badge/Spring%20Boot-4-brightgreen)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

[Quick start](#quick-start) · [Demo](#demo) · [Examples](docs/examples.md) ·
[Guarantees](#guarantees) · [Design](docs/design.md) · [Roadmap](docs/roadmap.md)

</div>

> [!NOTE]
> **Pre-release.** The v0.1 features are implemented, but nothing is published yet, so the coordinates below only resolve once 0.1.0 is on Maven
> Central.

## One declaration, every boundary

Declare a key once, and say where it may travel:

```java
static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);

@Bean
ContextPolicy applicationPolicy() {
    return ContextPolicy.builder()
            .key(TENANT, rules -> rules
                    .codec(Codecs.string())
                    .wireName("Tenant-Id")
                    .required()
                    .loggable()
                    .propagateTo(Boundary.ATTACHED, Boundary.DETACHED, Boundary.INTERNAL_CALL, Boundary.MESSAGE))
            .build();
}
```

The library then enforces it across your Spring Boot services:

| Where the request goes | What happens to `tenant` |
|---|---|
| A decorated thread pool | It follows the work |
| A call to one of your own services | It is sent as `Tenant-Id: acme` |
| A Kafka message sent through a `KafkaTemplate` bean | It travels with the record, and a record listener receives it |
| A call to a third party | It is not sent, and if your code sets the header anyway, the header is removed and reported |
| A thread pool nobody decorated | It is lost, and the next internal call or Kafka send fails with `MISSING_REQUIRED` before sending |
| A `Tenant-Id` HTTP header forged by a public client | It is ignored and reported |

## Why

In a Spring microservice, tenant, user, locale, correlation identifiers and time budget all have to cross thread pools, `@Async` methods, scheduled
jobs, HTTP calls, Kafka messages and other services. Hand-written MDC filters, TaskDecorators and interceptors fail in predictable ways:

| Without a contract | With `boundrail-context` |
|---|---|
| **Context is silently lost** at an executor nobody decorated | Losing a required key is a violation, and a failing test before it reaches production |
| **Context leaks**, such as an internal header forwarded to a third-party API | Outbound calls and messages carry only the keys that list that boundary |
| **Context is forged** because a service trusts a header that a public client can set | HTTP context headers are ignored until you say which callers to trust |
| **Deadlines do not travel**, so downstream services keep working after the caller gave up | Each internal hop sends the time that is left, and a call made after it runs out fails before sending |

Micrometer and OpenTelemetry solve the mechanics of moving context. `boundrail-context` answers the application-level questions: what context exists,
where it came from, where it is allowed to go, and what happens if it disappears.

## Features

- **Typed context keys** with a propagation policy for every key at every kind of boundary.
- **Trust rules** for which incoming context is accepted and which context may leave.
- **Deadline budgets** that shrink as a request travels, and that a binding can shorten but never extend.
- **Identity** that separates the authority a call runs with from the initiator who caused the work.
- **Diagnostics** that turn lost context into a test failure instead of a production mystery.
- **Two runtime backends:** `ScopedValue` on Java 25+ and `ThreadLocal` on Java 21–24, selected automatically through a multi-release JAR.
- **A core with no runtime dependencies**, usable from plain Java.

## Quick start

You need Java 21 or later, and Spring Boot 4 for the Spring and Kafka modules. v0.1 covers servlet Spring MVC, `RestClient` and `RestTemplate`, but
not WebFlux or `WebClient`.

**1. Add the dependencies.**

```kotlin
dependencies {
    implementation("io.github.boundrail:boundrail-context-spring:0.1.0")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")

    testImplementation("io.github.boundrail:boundrail-context-test:0.1.0")  // JUnit extension and assertions
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
```

<details>
<summary>Plain Java, Kafka and metrics</summary>

Plain Java needs only the core, which has no runtime dependencies:

```kotlin
dependencies {
    implementation("io.github.boundrail:boundrail-context-core:0.1.0")
}
```

The Spring module brings no web stack of its own, so add the Boot starters for what you use:

| Starter | Needed for |
|---|---|
| `spring-boot-starter-webmvc` | Inbound filters |
| `spring-boot-starter-restclient` | `RestClient` and `RestTemplate` support |
| `spring-boot-starter-security-oauth2-resource-server` | Identity |
| `spring-boot-starter-kafka` | Kafka, together with `io.github.boundrail:boundrail-context-kafka:0.1.0` |
| `spring-boot-starter-actuator` | A `MeterRegistry` for the violation and deadline counters |

</details>

**2. Declare your policy** as a bean, as [shown above](#one-declaration-every-boundary).

**3. Take the tenant from the verified token.** At a public edge, a value comes from authentication and not from a header. Configure Spring Security's
JWT resource server, or every request gets a 401, and add a contributor:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://your-issuer.example
```

```java
@Bean
JwtClaimContributor tenantClaim() {
    return JwtClaimContributor.of("tenant", TENANT, Codecs.string());
}
```

Your controllers then read the key like any other: `TENANT.get()`.

**4. Say where each client points.** Build clients from the auto-configured `RestClient.Builder`. A client built with `RestClient.builder()` doesn't
get the library's interceptor, so it sends no context and nothing is checked.

```java
@Service
class OrderClient {

    private final RestClient orders;
    private final RestClient partner;

    OrderClient(RestClient.Builder clients) {
        orders = clients.clone().apply(BoundrailClients.internal("orders")).baseUrl("http://orders").build();
        partner = clients.clone().apply(BoundrailClients.external()).baseUrl("https://api.partner.example").build();
    }

    String order(String id) {
        return orders.get().uri("/orders/{id}", id).retrieve().body(String.class);
    }
}
```

**5. Trust your own callers, on internal services only.** By default no caller is trusted, so a service behind the edge needs this to receive the
context the edge sends:

```yaml
boundrail:
  context:
    inbound:
      trust: cidr
      trusted-cidrs: ["10.0.0.0/8"]
```

> [!WARNING]
> Set this only on services that public traffic can't reach, and never on a range that contains your ingress or load balancer. The check uses
> `getRemoteAddr()`, so a proxy inside the range makes every public request trusted, and a trusted header wins over a token claim. With
> forwarded-header processing on, a client that can forge those headers can decide the address that is checked.

## Demo

Three demos run against services in Docker Compose. You need only Docker; the demo is built inside the image.

```bash
./examples/propagation-demo/run-demo.sh
```

Output from a real run (the numbers vary per run):

```text
Demo 1: context loss is caught by a test
  test      a plain executor lost the tenant
  caught    MISSING_REQUIRED, key tenant, boundary INTERNAL_CALL

Demo 2: a tenant is stripped from an external call
  edge      set Tenant-Id: acme on the call to partner
  partner   received no Tenant-Id
  edge      reported FORBIDDEN_HEADER

Demo 3: a deadline shrinks across hops, and a Kafka event is unaffected
  edge      1999 ms left, then 100 ms of simulated work
  orders    1898 ms left
  event     handled after the deadline passed, tenant acme, no deadline
```

## Examples

Context has no setters. You bind values for the length of a scope, and they are gone when it ends:

```java
Context.with(TENANT, "acme").run(() -> {
    TENANT.get();                                   // "acme"
    Context.with(TENANT, "globex").run(() -> {
        TENANT.get();                               // "globex"
    });
    TENANT.get();                                   // "acme" again
});
```

A plain thread pool loses the context, and `BoundExecutors` keeps it:

```java
ExecutorService plain = Executors.newFixedThreadPool(4);
ExecutorService pool = BoundExecutors.attached(plain);

Context.with(TENANT, "acme").run(() -> {
    plain.submit(() -> TENANT.isBound());           // false: the context is lost
    pool.submit(() -> TENANT.get());                // "acme"
});
```

Give incoming requests a budget, and every hop sends what's left of it to the next internal service:

```yaml
boundrail:
  context:
    deadline:
      default-inbound: 2s    # when no trusted deadline arrives; unset by default
      max-inbound: 30s       # the cap on a trusted inbound deadline
      expired-status: 504
```

Lost context becomes a failing test. Here `orders` is the client from the quick start:

```java
@BoundrailTest
@SpringBootTest
class ContextLossTest {

    @Autowired
    OrderClient orders;

    @Test
    void aPlainExecutorLosesTheTenant() {
        ExecutorService plain = Executors.newSingleThreadExecutor();

        Future<String> call = Context.with(TENANT, "acme").call(() -> plain.submit(() -> orders.order("42")));

        assertThatThrownBy(call::get).hasCauseInstanceOf(ContextViolationException.class);
        ContextAssertions.assertThatViolations().hasSize(1).containsKind(Violation.Kind.MISSING_REQUIRED);
    }
}
```

**[See all 14 examples →](docs/examples.md)**, from plain Java keys to Spring Boot services, covering policy, trust, logs, async work, identity, Kafka
and extension points.

## Guarantees

Each guarantee is proved by an acceptance test.

| Guarantee | Proved by |
|---|---|
| A decorated executor carries the context | [IT-1.1][it-1] |
| An undecorated executor fails loudly at the next internal call | [IT-1.2][it-1] |
| Spring MVC async processing keeps the request's context | [IT-1.9][it-1] |
| An internal call carries the tenant | [IT-2.1][it-2] |
| An external call carries no context by default, and an unknown destination counts as external | [IT-2.2, IT-2.3][it-2] |
| A context header set by application code on an external call is stripped and reported | [IT-2.4][it-2] |
| A tenant forged by a public caller is ignored | [IT-2.5][it-2] |
| A deadline budget shrinks across hops | [IT-3.1][it-3] |
| With a deadline bound and the JDK `HttpClient` request factory, a slow call stops when the budget runs out | [IT-3.2][it-3] |
| An expired deadline stops a call before sending, and a request on arrival | [IT-3.3, IT-3.4][it-3] |
| A Kafka event outlives the request that sent it | [IT-3.8][it-3] |
| The authority never reaches an external call or detached work | [IT-4.5][it-4] |
| The caller's token never appears in logs, the MDC, violations or a snapshot's `toString()` | [IT-4.6][it-4] |
| Messages carry the initiator, never the authority | [IT-4.9][it-4] |
| A binding can shorten a deadline, but never extend or remove it, on both backends | [RC-8][rc] |

## Limitations

v0.1 draws these lines on purpose. The Javadoc of `BoundExecutors`, `BoundrailClients` and `KafkaContext` has the full detail.

**Threads**

- `CompletableFuture.*Async` methods without an explicit executor use the common pool and are not covered.
- `@Scheduled` methods returning a reactive `Publisher`, and Kotlin `suspend` `@Scheduled` methods, get no system context.
- The startup audit sees only singleton executor beans of the application context that are instantiated at startup. Executors created in code are
  checked only at the next crossing through a library adapter, and only for required keys.
- `BoundExecutors` methods without a policy argument apply only the built-in rules, so pass your policy to them.

**HTTP**

- A redirect the HTTP client follows is not classified again, so it can carry the headers written for the original destination. Turn off redirect
  following on internal clients.

**Kafka**

- Only listener containers created by listener container factory beans are scoped.
- Batch listeners are not scoped. Use `forRecord` to enter each record's snapshot.
- Kafka record headers are trusted by default. Set `boundrail.context.kafka.trust-headers=false` to turn that off.
- The deadline is neither sent nor applied when a record is sent.
- `RecordInterceptor`s run outside the listener scope, and a listener whose container advice chain is replaced is not scoped.
- Re-sending the same `ProducerRecord` instance is not supported.
- A record whose decoding throws something other than `IllegalArgumentException`, in `fail` mode with a required `MESSAGE` key, can't be dead-lettered
  and is redelivered.

## Modules

| Module | Contents |
|---|---|
| `boundrail-context-core` | Keys, snapshots, scopes, policy, codecs, destination classification, deadline and identity types, both backends; zero runtime dependencies |
| `boundrail-context-spring` | Spring Boot 4 auto-configuration: servlet filters, trust, RestClient/RestTemplate, executors, `@Scheduled`, MDC, audit, security |
| `boundrail-context-kafka` | Spring Kafka producer and consumer adapters |
| `boundrail-context-test` | JUnit Jupiter extension, assertions and a test trust verifier |

`boundrail-context` is the context module of **Boundrail**, the parent framework for declared service contracts in Java. Building from source requires
a JDK 25 toolchain.

## Documentation

- [examples.md](docs/examples.md): every feature, from plain Java to a set of Spring Boot services.
- [design.md](docs/design.md): what the project is, why it exists, and every design decision.
- [roadmap.md](docs/roadmap.md): milestones from an empty repository to the v0.1 release.
- [specs/](specs/): normative requirements and acceptance scenarios, one Spec Kit feature per milestone.

## Licence

Apache License 2.0. See [LICENSE](LICENSE).

[it-1]: integration-tests/src/test/java/io/github/boundrail/context/it/ContextPropagationIT.java
[it-2]: integration-tests/src/test/java/io/github/boundrail/context/it/TrustBoundaryIT.java
[it-3]: integration-tests/src/test/java/io/github/boundrail/context/it/DeadlinePropagationIT.java
[it-4]: integration-tests/src/test/java/io/github/boundrail/context/it/AuthorityPropagationIT.java
[rc]: integration-tests/src/test/java/io/github/boundrail/context/it/RuntimeConformanceIT.java
