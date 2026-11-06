# boundrail-context

> **`boundrail-context` defines what context means, where it may travel, and how deadlines behave across service boundaries.**
> Micrometer can move context. `boundrail-context` defines its contract.

`boundrail-context` is the context module of **Boundrail**, the parent framework for declared service contracts in Java.

> **Status:** pre-release. The v0.1 features are implemented and development follows the milestones in [roadmap.md](docs/roadmap.md). There is no
> published artifact yet, so the coordinates below only resolve once 0.1.0 is on Maven Central.

## Why

In a Spring microservice, request context such as tenant, user, locale, correlation identifiers and time budget has to cross thread pools, `@Async`
methods, scheduled jobs, outgoing HTTP calls, Kafka messages and other services. Hand-written MDC filters, TaskDecorators and interceptors fail in
predictable ways:

- **Context is silently lost** at an executor nobody decorated.
- **Context leaks** where it should not go, such as an internal header forwarded to a third-party API.
- **Context is forged** because a service trusts a header that a public client can set.
- **Deadlines do not travel**, so downstream services keep working after the caller gave up.

Micrometer and OpenTelemetry solve the mechanics of moving context. `boundrail-context` answers the application-level questions: what context exists,
where it came from, where it is allowed to go, and what happens if it disappears.

## What it provides

- **Typed context keys** with a propagation policy for every key at every kind of boundary.
- **Trust rules** for which incoming context is accepted and which context may leave.
- **Deadline budgets** that shrink as a request travels and can be shortened but never extended.
- **Identity** that separates the authority a call runs with from the initiator who caused the work.
- **Diagnostics** that turn lost context into a test failure instead of a production mystery.
- **Two runtime backends:** `ScopedValue` on Java 25+ and `ThreadLocal` on Java 21–24, selected automatically through a multi-release JAR.

## Modules

| Module | Contents |
|---|---|
| `boundrail-context-core` | Keys, snapshots, scopes, policy, codecs, destination classification, deadline and identity types, both backends; zero runtime dependencies |
| `boundrail-context-spring` | Spring Boot 4 auto-configuration: servlet filters, trust, RestClient/RestTemplate, executors, `@Scheduled`, MDC, audit, security |
| `boundrail-context-kafka` | Spring Kafka producer and consumer adapters |
| `boundrail-context-test` | JUnit Jupiter extension, assertions and a test trust verifier |

## Requirements

- Java 21 or later at runtime; building requires a JDK 25 toolchain.
- Spring Boot 4 (Spring Framework 7) for the Spring and Kafka modules.
- v0.1 covers servlet Spring MVC, `RestClient` and `RestTemplate`. WebFlux and `WebClient` are not supported.

## Install

Plain Java needs only the core, which has no runtime dependencies:

```kotlin
dependencies {
    implementation("io.github.boundrail:boundrail-context-core:0.1.0")
}
```

In a Spring Boot 4 application, use the Spring module, and add the others as you need them:

```kotlin
dependencies {
    implementation("io.github.boundrail:boundrail-context-spring:0.1.0")
    implementation("io.github.boundrail:boundrail-context-kafka:0.1.0")     // Kafka producers and listeners
    testImplementation("io.github.boundrail:boundrail-context-test:0.1.0")  // JUnit extension and assertions
}
```

The Spring module brings no web stack of its own, so add the Boot starters for what you use:

| Starter | Needed for |
|---|---|
| `spring-boot-starter-webmvc` | Inbound filters |
| `spring-boot-starter-restclient` | `RestClient` and `RestTemplate` support |
| `spring-boot-starter-security-oauth2-resource-server` | Identity |
| `spring-boot-starter-kafka` | Kafka |
| `spring-boot-starter-actuator` | A `MeterRegistry` for the violation and deadline counters |

## Examples

The examples start with plain Java and build up to a set of Spring Boot services. They share these keys:

```java
static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
static final ContextKey<Locale> LOCALE = ContextKey.of("locale", Locale.class, Locale.ENGLISH);
static final ContextKey<Long> PRIORITY = ContextKey.of("priority", Long.class);
```

**Plain Java:** [1. Keys and scopes](#1-keys-and-scopes) · [2. Other threads](#2-other-threads) · [3. Deadlines](#3-deadlines) ·
[4. Policy](#4-policy)

**Spring Boot:** [5. Outbound calls](#5-outbound-calls) · [6. Inbound requests](#6-inbound-requests) ·
[7. Required keys and violations](#7-required-keys-and-violations) · [8. Logs](#8-logs) ·
[9. Async work and scheduled jobs](#9-async-work-and-scheduled-jobs) ·
[10. Deadlines across services](#10-deadlines-across-services) · [11. Identity](#11-identity) · [12. Kafka](#12-kafka) ·
[13. Testing](#13-testing) · [14. Extension points](#14-extension-points)

### 1. Keys and scopes

Context is immutable and has no setters. You bind values for the length of a scope, and they are gone when it ends.

```java
Context.with(TENANT, "acme").run(() -> {
    TENANT.get();                                   // "acme"
    Context.with(TENANT, "globex").run(() -> {
        TENANT.get();                               // "globex"
    });
    TENANT.get();                                   // "acme" again
    Context.without(TENANT).run(() -> {
        TENANT.isBound();                           // false
    });
});

TENANT.find();                                      // Optional.empty
TENANT.get();                                       // throws MissingContextException
LOCALE.get();                                       // Locale.ENGLISH, its default
LOCALE.isBound();                                   // false: a default does not count as bound
```

Use `call` to return a value, and chain `with` to bind several keys at once:

```java
String label = Context.with(TENANT, "acme")
        .with(LOCALE, Locale.FRENCH)
        .call(() -> TENANT.get() + "/" + LOCALE.get());  // "acme/fr"
```

### 2. Other threads

A plain thread pool loses the context. `BoundExecutors` captures it when the task is submitted and runs the task inside it.

```java
ExecutorService plain = Executors.newFixedThreadPool(4);
ExecutorService pool = BoundExecutors.attached(plain);

Context.with(TENANT, "acme").run(() -> {
    plain.submit(() -> TENANT.isBound());           // false: the context is lost
    pool.submit(() -> TENANT.get());                // "acme"
});
```

The mode says how the work relates to its caller. Choose `attached` when the caller waits for the result, and `detached` for work that can outlive
it. Detached work does not carry the deadline or the authority.

```java
ExecutorService awaited = BoundExecutors.attached(plain);
ExecutorService background = BoundExecutors.detached(plain);

Context.with(TENANT, "acme").with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ofSeconds(2))).run(() -> {
    awaited.submit(() -> Deadlines.current().isPresent());     // true
    background.submit(() -> Deadlines.current().isPresent());  // false
    background.submit(() -> TENANT.get());                     // "acme"
});
```

When you can't wrap the executor, capture the context yourself. Declare the task as a `Runnable` or a `Callable`, because `wrap` accepts both:

```java
Runnable work = () -> System.out.println(TENANT.get());
Runnable bound = Context.with(TENANT, "acme").call(() -> Context.capture().wrap(work));
plain.submit(bound);                                // prints "acme"
```

### 3. Deadlines

A deadline is measured on a monotonic clock and travels as remaining time. It can be shortened but never extended, so code inside a scope can't give
itself more time than the scope has.

```java
Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ofSeconds(2))).run(() -> {
    Deadline deadline = Deadlines.current().orElseThrow();
    deadline.remaining();                           // at most 2 seconds, and never negative
    deadline.check();                               // throws DeadlineExceededException once it has expired

    Deadline tighter = deadline.within(Duration.ofMillis(500));  // the earlier of the two
    Context.with(BuiltInKeys.DEADLINE, tighter).run(() -> {
        Deadlines.current().orElseThrow().remaining();  // at most 500 ms
    });

    Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ofMinutes(1))).run(() -> {
        Deadlines.current().orElseThrow().remaining();  // still at most 2 seconds
    });
});
```

`Context.without(BuiltInKeys.DEADLINE)` throws `IllegalArgumentException`. Work that must outlive a deadline belongs on a detached executor.

### 4. Policy

A policy states where each key may travel. A key registered in a policy goes only to the boundaries it lists, and a key that is not registered stays
in the process (`ATTACHED` and `DETACHED`). None of your keys leaves the process unless you declare it. The built-in keys have fixed rules: the
initiator and the deadline go to internal calls, the initiator also goes to messages, and the authority goes to internal calls through its propagator.

| Boundary | Crossing |
|---|---|
| `ATTACHED` | In-process work that stays part of the current operation |
| `DETACHED` | In-process work that can outlive the current operation |
| `INTERNAL_CALL` | An HTTP call to one of your own services; inbound HTTP requests bind the keys that list it |
| `EXTERNAL_CALL` | An HTTP call to anything else |
| `MESSAGE` | A message sent or received through a broker |

```java
ContextPolicy policy = ContextPolicy.builder()
        .key(TENANT, rules -> rules
                .codec(Codecs.string())
                .wireName("Tenant-Id")
                .required()
                .loggable()
                .propagateTo(Boundary.ATTACHED, Boundary.DETACHED, Boundary.INTERNAL_CALL, Boundary.MESSAGE))
        .build();

policy.propagates(TENANT, Boundary.INTERNAL_CALL);  // true
policy.propagates(TENANT, Boundary.EXTERNAL_CALL);  // false
```

To apply your policy in plain Java, pass it to the executor: `BoundExecutors.detached(plain, policy)`. In Spring Boot, the adapters and decorated
executors use the merged policy, but `BoundExecutors` still needs it passed ([section 9](#9-async-work-and-scheduled-jobs)).

### 5. Outbound calls

Declare your policy as a bean. It is merged with the built-in keys, and a conflict fails startup.

```java
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

Build clients from the auto-configured `RestClient.Builder`, and say where each one points. A client built with `RestClient.builder()` doesn't get the
library's interceptor.

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

    void notifyPartner(String id) {
        partner.post().uri("/events").body(id).retrieve().toBodilessEntity();
    }
}
```

Clients with no declaration are classified by host. A host that matches neither list is `UNKNOWN`, which is treated exactly like external.

```yaml
boundrail:
  context:
    destinations:
      internal-hosts: ["*.svc.cluster.local"]
      external-hosts: ["*.partner.example"]
```

With `tenant` and a deadline bound, the two clients send:

| Header | `orders` (internal) | `partner` (external) |
|---|---|---|
| `Tenant-Id` | `acme` | not sent |
| `Boundrail-Deadline-Ms` | remaining milliseconds | not sent |

If application code sets `Tenant-Id` on the external call anyway, the header is removed and a `FORBIDDEN_HEADER` violation is reported.

A redirect the HTTP client follows is not classified again, so the redirected request can carry the headers written for the original destination. Turn
off redirect following on internal clients.

### 6. Inbound requests

Context headers are trusted only when you say so. By default no caller is trusted: a `Tenant-Id` header is ignored, reported as `UNTRUSTED_HEADER`,
and the request carries on. Trust your own network explicitly:

```yaml
boundrail:
  context:
    inbound:
      trust: cidr
      trusted-cidrs: ["10.0.0.0/8"]
```

The check uses `getRemoteAddr()`. With forwarded-header processing on, a client that can forge those headers can decide the address that is checked.

At the edge, take values from verified authentication instead of a header. With Spring Security, a contributor runs after authentication. A key
already bound from a trusted header keeps that value, so don't trust callers that shouldn't set it.

```java
@Bean
JwtClaimContributor tenantClaim() {
    return JwtClaimContributor.of("tenant", TENANT, Codecs.string());
}
```

Your controllers then read the key like any other: `TENANT.get()`.

### 7. Required keys and violations

A key marked `required()` must be bound whenever the library sends a call or message over a boundary the key propagates to. Inbound requests and
records are not checked. If it isn't bound, the default `fail` mode reports the violation and throws `ContextViolationException` before sending:

```text
Context violation MISSING_REQUIRED for key tenant at boundary INTERNAL_CALL
```

Use `report` mode while you adopt the library. The violation is reported and the call continues without the key. Every other kind of violation is
always report-only.

```yaml
boundrail:
  context:
    violations:
      mode: report
      log-interval: 1m
```

Violations are logged at WARN, rate-limited per kind, key and boundary. Where the application has a `MeterRegistry` bean, every occurrence also counts
in `boundrail.context.violations`. Violations never contain the value. To send them somewhere else as well, add a listener:

```java
@Bean
ViolationListener violationAlerts() {
    return violation -> System.out.printf("%s for %s at %s%n", violation.kind(), violation.key(), violation.boundary());
}
```

### 8. Logs

Keys marked `loggable()` are put in the MDC for as long as they are bound, and the previous values are restored afterwards. The authority and the
deadline are never written to it.

```yaml
logging:
  pattern:
    console: "%d %5p [%t] tenant=%X{tenant} %logger{40} : %m%n"
```

### 9. Async work and scheduled jobs

Spring's `ThreadPoolTaskExecutor`, `SimpleAsyncTaskExecutor` and `ThreadPoolTaskScheduler` beans are decorated automatically, in `detached` mode
unless you set another mode by bean name. With `@EnableAsync`, and a caller that waits for the result:

```java
@Bean
ThreadPoolTaskExecutor reportExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(4);
    return executor;
}

@Async("reportExecutor")
public CompletableFuture<String> buildReport() {
    return CompletableFuture.completedFuture(TENANT.get());  // the caller's tenant
}
```

```yaml
boundrail:
  context:
    executors:
      modes:
        reportExecutor: attached
    audit:
      fail-on-gap: true
```

On the default detached mode, a task has no deadline and no authority, so its internal calls send no `Authorization`.

Other `Executor` beans aren't wrapped for you, so wrap them yourself and pass the policy. Keys from any other `ContextPolicy` bean are treated as
unregistered here:

```java
@Bean
ExecutorService importExecutor(ContextPolicy applicationPolicy) {
    return BoundExecutors.detached(Executors.newFixedThreadPool(4), applicationPolicy);
}
```

At startup, an audit logs every executor bean it can see:

```text
Executor audit: importExecutor COVERED (detached)
Executor audit: legacyPool GAP
Executor audit: reportExecutor COVERED (attached)
Executor audit: 2 covered, 1 gaps
```

With `fail-on-gap`, a gap fails startup. With `@EnableScheduling`, an ordinary `@Scheduled` method runs in a fresh context that holds only an
initiator, such as `system:scheduled:cleanupJob.run`, so nothing leaks in from an earlier task.

### 10. Deadlines across services

Give incoming requests a budget, and every hop sends what's left of it to the next internal service. A receiver honours it only from a trusted caller
([section 6](#6-inbound-requests)). Otherwise it reports the header and applies its own `default-inbound`, if set.

```yaml
boundrail:
  context:
    deadline:
      default-inbound: 2s    # when no trusted deadline arrives; unset by default
      max-inbound: 30s       # the cap on a trusted inbound deadline
      expired-status: 504
```

A request whose deadline has already expired gets `expired-status` with an empty body, and its handler never runs. By default, a
`DeadlineExceededException` that escapes a handler also becomes `expired-status`, unless your own `@ExceptionHandler` handles it.

On outbound calls:

- A call made after the deadline expired fails before sending, with stage `BEFORE_SEND`.
- A call still waiting for its response headers when time runs out is stopped, with stage `TIMEOUT`, on internal and external calls alike. This is
  guaranteed only with the JDK `HttpClient` request factory (`spring.http.clients.imperative.factory=jdk`). The Simple and Apache HttpComponents
  factories on platform threads are not capped, and other factories are cut off only as far as they honour the interrupt. The `BoundrailClients`
  Javadoc has the details, and a WARN at startup tells you when Boot's factory is not the JDK one.
- A timeout while reading the body, or a shorter timeout configured on the client, surfaces as the client's own exception.

To react to an expired deadline yourself, catch the exception:

```java
try {
    orderClient.order("42");
} catch (DeadlineExceededException e) {
    DeadlineExceededException.Stage stage = e.stage();
}
```

### 11. Identity

The library keeps two things apart: the **authority** a call runs with, and the **initiator** who caused the work.

```java
BuiltInKeys.AUTHORITY.find().map(Authority::subject);   // the library binds it only from verified authentication
BuiltInKeys.INITIATOR.find().map(Initiator::value);     // for example "user:alice"
```

With Spring Security's OAuth2 resource server, a verified token binds the authority, and the initiator is derived as `user:<name>`. On the request
thread and on attached work, internal calls then carry `Authorization: Bearer <credential>`. Detached work, scheduled jobs and Kafka listeners have no
authority, so their calls send none, and a service without Spring Security binds none of its own. The library never adds the credential to an external
or unknown destination. An `Authorization` header your own code sets is left alone.

To avoid forwarding the caller's token, exchange it (RFC 8693) for one scoped to each destination. The audience is the service name, from
`BoundrailClients.internal("orders")` or a classifier that names the service. Without one, the call is sent with no credential and `MISSING_AUDIENCE`
is reported. This option needs Jackson 3 on the classpath.

```yaml
boundrail:
  context:
    authority:
      propagation: exchange
      exchange:
        token-uri: https://auth.example/oauth2/token
        client-id: edge
        client-secret: ${TOKEN_EXCHANGE_SECRET}
```

### 12. Kafka

Add `boundrail-context-kafka` and keep using Spring Kafka as usual. Through a `KafkaTemplate` bean, keys that propagate to `MESSAGE` are written as
record headers on the sending thread:

```java
void orderPlaced(String id) {
    kafka.send("orders.events", id);                // the record carries Tenant-Id
}
```

A record-mode listener runs in exactly the context its record carried. The deadline and the authority never travel in a message, so an event processed
long after the request still succeeds and still knows the tenant and the initiator.

```java
@KafkaListener(topics = "orders.events")
void onOrder(String orderId) {
    String tenant = TENANT.get();                   // from the record's headers
}
```

Batch listeners are not scoped automatically. Use the `KafkaContext` bean to enter each record's context yourself:

```java
@KafkaListener(topics = "orders.events", batch = "true")
void onOrders(List<ConsumerRecord<String, String>> records) {
    for (ConsumerRecord<String, String> record : records) {
        kafkaContext.forRecord(record).run(() -> charge(record.value()));
    }
}
```

Record headers are trusted by default. To turn that off, set `boundrail.context.kafka.trust-headers=false`: nothing is then bound from a record, and
each policy header present is reported. The `KafkaContext` Javadoc lists what is covered and where coverage stops.

### 13. Testing

`boundrail-context-test` turns lost context into a failing test. `@BoundrailTest` records every violation reported during a test method and turns
capture sites on, so a violation raised inside a bound scope says where that scope's context was created. Context lost entirely, as in this example,
has no capture site.

```java
@BoundrailTest
@SpringBootTest(properties = "boundrail.context.violations.mode=report")
class ContextLossTest {

    @Autowired
    OrderClient orders;

    @Test
    void aPlainExecutorLosesTheTenant() throws Exception {
        ExecutorService plain = Executors.newSingleThreadExecutor();

        Context.with(TENANT, "acme").call(() -> plain.submit(() -> orders.order("42"))).get();

        ContextAssertions.assertThatViolations().hasSize(1).containsKind(Violation.Kind.MISSING_REQUIRED);
    }
}
```

You can also assert on a snapshot:

```java
ContextSnapshot outbound = Context.with(TENANT, "acme").with(LOCALE, Locale.FRENCH)
        .call(() -> policy.filter(Context.capture(), Boundary.INTERNAL_CALL));

ContextAssertions.assertThat(outbound).contains(TENANT, "acme").doesNotContain(LOCALE);
```

To test trusted calls between services, register `TestInboundTrustVerifier` as a bean on the server, and apply `TestInboundTrustVerifier.peer()` to
the client's `RestClient.Builder`.

### 14. Extension points

Each of these replaces or adds to a default.

**Your own value types.** Give the key a codec. `decode` signals bad input with `IllegalArgumentException`, so the key stays unbound and an
`INVALID_VALUE` violation is reported.

```java
record CustomerId(String value) {}

static final ContextKey<CustomerId> CUSTOMER = ContextKey.of("customer", CustomerId.class);

static final ContextCodec<CustomerId> CUSTOMER_CODEC = new ContextCodec<>() {
    @Override
    public String encode(CustomerId id) {
        return id.value();
    }

    @Override
    public CustomerId decode(String text) {
        if (!text.startsWith("c-")) {
            throw new IllegalArgumentException("not a customer id");
        }
        return new CustomerId(text);
    }
};
```

```java
ContextPolicy policy = ContextPolicy.builder()
        .key(CUSTOMER, rules -> rules
                .codec(CUSTOMER_CODEC)
                .wireName("Customer-Id")
                .propagateTo(Boundary.ATTACHED, Boundary.INTERNAL_CALL))
        .build();
```

**Your own contributions.** Derive values from the authenticated user. A key already bound from a trusted header keeps that value, and among
contributions the first wins, in bean order. `AUTHORITY` cannot be contributed.

```java
@Bean
ContextContributor priorityFromRoles() {
    return (authentication, contributions) -> {
        boolean premium = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_PREMIUM".equals(authority.getAuthority()));
        contributions.put(PRIORITY, premium ? 10L : 1L);
    };
}
```

**Your own trust rule.** This replaces the built-in verifier:

```java
@Bean
InboundTrustVerifier gatewayVerifier() {
    return request -> "10.0.0.1".equals(request.getRemoteAddr())
            ? InboundTrustVerifier.Trust.TRUSTED_INTERNAL
            : InboundTrustVerifier.Trust.UNTRUSTED;
}
```

**Your own classification.** This replaces the host patterns. Check `declared()` first, or a client's own declaration is ignored:

```java
@Bean
DestinationClassifier destinations() {
    HostPatternClassifier hosts = HostPatternClassifier.of(List.of("*.svc.cluster.local"), List.of("*.partner.example"));
    return request -> request.declared().isEmpty() && "billing.internal.example".equals(request.uri().getHost())
            ? new Destination(Destination.Kind.INTERNAL, Optional.of("billing"))
            : hosts.classify(request);
}
```

**Your own credential rule.** This replaces `forward` and `exchange`. It is called only for internal destinations, and returning empty sends no
credential.

```java
@Bean
AuthorityPropagator authorityPropagator() {
    return (authority, destination) -> destination.service().filter("audit"::equals).map(service -> authority.credential());
}
```

## Documentation

- [design.md](docs/design.md): what the project is, why it exists, and every design decision.
- [roadmap.md](docs/roadmap.md): milestones from an empty repository to the v0.1 release.
- [specs/](specs/): normative requirements and acceptance scenarios, one Spec Kit feature per milestone.

## Licence

Apache License 2.0. See [LICENSE](LICENSE).
