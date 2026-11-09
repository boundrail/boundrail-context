# Configuration reference

Generated from the Configuration tables in `specs/`; do not edit by hand.
Regenerate with `./gradlew :integration-tests:generateConfigurationReference`.
Property names in the Meaning column are relative to `boundrail.context.`.

## Core and Runtimes

| Property | Default | Meaning |
|---|---|---|
| `boundrail.context.backend` (JVM system property) | `auto` | `auto` or `thread-local`, compared exactly; read when the runtime is first used (RT-04) |

## HTTP Boundaries and Trust

| Property | Default | Meaning |
|---|---|---|
| `boundrail.context.violations.mode` | `fail` | `fail` or `report`, matched with Spring Boot's relaxed enum binding (ignoring case, surrounding whitespace and non-alphanumeric characters); an empty or blank value means `fail`, and any value that matches neither, such as `bogus`, fails startup, even under lazy initialization (DIAG-02) |
| `boundrail.context.violations.log-interval` | `1m` | Rate limit for violation logs; `0s` logs every occurrence, and a negative value fails startup (DIAG-03) |
| `boundrail.context.inbound.trust` | `none` | `none` or `cidr`, matched with Spring Boot's relaxed enum binding (ignoring case, surrounding whitespace and non-alphanumeric characters); an empty or blank value means `none`, and any value that matches neither, such as `bogus`, fails startup, even under lazy initialization; a verifier bean overrides it (IN-03) |
| `boundrail.context.inbound.trusted-cidrs` | `[]` | IPv4 or IPv6 CIDRs, or bare addresses, trusted when `inbound.trust=cidr` (IN-03, IN-08). IPv4 octets and prefix lengths are plain decimal with no leading zeros, and entries are never in square brackets; with `inbound.trust=cidr`, an invalid entry fails startup. Matched against `getRemoteAddr()`, after removing one enclosing pair of square brackets (`[2001:db8::1]`); a remote address that is not a literal in that form is untrusted. With forwarded-header processing enabled, a proxy, or a client forging `Forwarded` or `X-Forwarded-For` headers that no proxy strips, decides that address |
| `boundrail.context.destinations.internal-hosts` | `[]` | Host patterns classified as internal (DEST-02, OUT-03) |
| `boundrail.context.destinations.external-hosts` | `[]` | Host patterns classified as external (DEST-02, OUT-03) |
| `boundrail.context.wire.max-value-length` | `256` | Maximum encoded wire value length in UTF-16 code units (`String.length()`), checked before decoding or character checks; not applied to JWT claims; a value less than 1 fails startup (WIRE-04, IN-06) |

## Async Work and Diagnostics

| Property | Default | Meaning |
|---|---|---|
| `boundrail.context.executors.auto-decorate` | `true` | Decorate Spring task executors (EXE-03) |
| `boundrail.context.executors.modes.<bean-name>` | `detached` | Mode of a decorated executor bean, its name matched ignoring case: `attached` or `detached`, matched with Spring Boot's relaxed enum binding; empty or blank means `detached`, and any other value, or two keys for one bean name with different modes, fails startup, even under lazy initialization (EXE-01) |
| `boundrail.context.diagnostics.capture-sites` | `off` | `off`, `on` or `sampled`, matched with Spring Boot's relaxed enum binding; empty or blank means `off`, and any other value fails startup, even under lazy initialization (DIAG-05); JVM-wide (RT-08), and applied only when set, so an unset property leaves the current mode unchanged |
| `boundrail.context.diagnostics.sample-rate` | `0.01` | Fraction recorded when sampled, from 0.0 to 1.0; any other value fails startup, even under lazy initialization (DIAG-05); JVM-wide (RT-08), and applied only when set; blank counts as not set |
| `boundrail.context.audit.enabled` | `true` | Startup executor audit and its log entries (DIAG-06); executor evidence (DIAG-04) does not depend on it |
| `boundrail.context.audit.fail-on-gap` | `false` | Fail startup when there are gaps (DIAG-06) |

## Deadlines

| Property | Default | Meaning |
|---|---|---|
| `boundrail.context.deadline.default-inbound` | unset | Budget applied when no trusted deadline is present (DL-03); a `Duration` with Spring Boot's binding, greater than zero; blank counts as not set; may exceed `deadline.max-inbound`; any other value fails startup, even under lazy initialization |
| `boundrail.context.deadline.max-inbound` | `30s` | Cap on trusted inbound deadlines (DL-03); a `Duration` with Spring Boot's binding, greater than zero; blank means `30s`; any other value fails startup, even under lazy initialization |
| `boundrail.context.deadline.expired-status` | `504` | Status for expired requests (DL-04, DL-08), from 400 to 599; any other value fails startup, even under lazy initialization |
| `boundrail.context.deadline.map-exception` | `true` | Map `DeadlineExceededException` to the status (DL-08); a boolean with Spring Boot's binding, and a value Boot cannot convert fails startup, even under lazy initialization |

## Identity

| Property | Default | Meaning |
|---|---|---|
| `boundrail.context.authority.propagation` | `forward` | `forward` or `exchange`, matched with Spring Boot's relaxed enum binding; an empty or blank value means `forward`, and any value that matches neither, such as `bogus`, fails startup, even under lazy initialization; an `AuthorityPropagator` bean overrides it (ID-04) |
| `boundrail.context.authority.exchange.token-uri` | unset | RFC 8693 token endpoint, an absolute `http` or `https` URI with a host and no userinfo; required when the built-in `exchange` propagator is selected, and any other value fails startup, even under lazy initialization (ID-11); its userinfo is never in the properties' `toString()`, whichever propagator is selected (ID-06) |
| `boundrail.context.authority.exchange.client-id` | unset | Client id for HTTP Basic authentication at the token endpoint; required, not blank, when the built-in `exchange` propagator is selected (ID-11) |
| `boundrail.context.authority.exchange.client-secret` | unset | Client secret for HTTP Basic authentication at the token endpoint; required, not blank, when the built-in `exchange` propagator is selected; never in a log entry, exception message or the properties' `toString()` (ID-11, ID-06) |

## Kafka

| Property | Default | Meaning |
|---|---|---|
| `boundrail.context.kafka.trust-headers` | `true` | Trust record headers (KAF-04); with `false`, nothing is bound from them and each policy wire header present is reported; a boolean with Spring Boot's binding, and a value Boot cannot convert fails startup, even under lazy initialization |
