# Feature Specification: HTTP Boundaries and Trust

**Feature Branch**: `003-http-trust`

**Created**: 2026-09-28

**Status**: Ready for planning

**Milestone**: M2

**Input**: Roadmap milestone [M2](../../docs/roadmap.md#m2--http-boundaries-and-trust).

API signatures: [contracts/http-api.md](contracts/http-api.md). Test topology: [001-foundation](../001-foundation/spec.md#test-topology).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Internal calls carry context, external calls carry none (Priority: P1)

A service calls other services with `RestClient` or `RestTemplate`. Context declared for internal calls travels to internal services as headers;
nothing reaches external or unknown destinations, even headers that application code set by hand.

**Why this priority**: Leaking internal context to third parties is the main failure the library prevents.

**Independent Test**: TrustBoundaryIT IT-2.1 – IT-2.4 and IT-2.9.

**Acceptance Scenarios** (TrustBoundaryIT):

1. **IT-2.1 Internal call carries tenant.** Given a public request authenticated as alice, when edge calls orders through a client declared
   `internal("orders")`, then orders observes `tenant=acme`. *(IN-04, IN-06, OUT-03, OUT-04, WIRE-01)*
2. **IT-2.2 External call carries no context.** When edge calls partner through a client declared `external()`, then partner receives none of
   `Tenant-Id`, `Priority`, `Boundrail-Initiator` or `Boundrail-Deadline-Ms`. *(OUT-04)*
3. **IT-2.3 Unknown means external.** When edge calls partner through a client with no declaration, whose host matches no pattern, then the result is
   the same as IT-2.2. *(OUT-02, OUT-03, DEST-02, DEST-03)*
4. **IT-2.4 Application-set forbidden headers are stripped.** When edge application code sets `Tenant-Id: acme` and
   `Authorization: Bearer partner-key` on a call declared external, then partner receives `Authorization: Bearer partner-key` and no `Tenant-Id`, and
   one `FORBIDDEN_HEADER` violation is reported. *(WIRE-07)*
5. **IT-2.9 Required key missing on an internal call.** Given violation mode `fail`, when edge code runs `Context.without(tenant)` and calls orders,
   then one `MISSING_REQUIRED` violation is reported, `ContextViolationException` is thrown, and orders receives no request. *(POL-08,
   DIAG-02)*

---

### User Story 2 - Forged context is rejected at the edge (Priority: P1)

Context headers are bound only from trusted internal callers. At the edge, context comes from verified authentication. Forged, invalid or oversized
values are ignored and reported, and never fail the request.

**Why this priority**: A service that trusts a header any public client can set is the classic context-forgery bug.

**Independent Test**: TrustBoundaryIT IT-2.5 – IT-2.8.

**Acceptance Scenarios** (TrustBoundaryIT):

1. **IT-2.5 Forged tenant from a public caller is ignored.** When a public caller authenticated as alice sends `Tenant-Id: evil`, then the edge
   handler observes `tenant=acme` (from the JWT claim, bound after authentication), one `UNTRUSTED_HEADER` violation is reported, and the request
   succeeds. *(IN-02, IN-04, IN-05)*
2. **IT-2.6 Forged tenant with no claim.** When a public caller whose JWT has no `tenant` claim sends `Tenant-Id: evil`, then `tenant` is unbound in
   the edge handler. *(IN-02, IN-06)*
3. **IT-2.7 Trusted caller's header is accepted.** When edge (a trusted caller) sends `Tenant-Id: acme` to orders, then orders binds `tenant=acme`.
   *(IN-02, IN-03, TEST-01)*
4. **IT-2.8 Invalid and oversized values.** When a trusted caller sends `Priority: abc`, then orders leaves `priority` unbound and one
   `INVALID_VALUE` violation is reported. When it sends a 300-character `Tenant-Id`, then orders leaves `tenant` unbound and one `OVERSIZED_VALUE`
   violation is reported. Both requests succeed, and neither the violation log entries nor the tags of the `boundrail.context.violations`
   counter contain `abc` or the `Tenant-Id` value. *(WIRE-04, WIRE-05, DIAG-09)*

---

### Edge Cases

- A policy wire header set by application code differs from the context value, on an internal, external or unknown call: OUT-04
  (application wins; on an external or unknown call, WIRE-07 still removes it when its key does not propagate to `EXTERNAL_CALL`).
- Application code sets a policy wire header name with no values: OUT-04 (unset, so the context value is written). Application code sets it
  to the empty string `""`: OUT-04 (set, so it is left unchanged).
- `spring.security.filter.order` is set, for example to `5`: IN-01 (`ContextInboundFilter` is ordered at `4`, still before Spring
  Security's filter chain); IN-04 (`ContextAuthenticationFilter` is ordered at `6`, still after Spring Security's filter chain).
- A request from which `ContextInboundFilter` binds no keys, such as any request under the default verifier: IN-01 (the rest of the chain
  runs in the caller's existing scope; no empty scope is entered).
- A handler uses Spring MVC async processing (`DeferredResult`, `Callable`): known limitation in M2 (see Assumptions; DESIGN Q8). The async
  dispatch runs without the inbound context.
- The HTTP client follows a 3xx redirect to another host: known limitation in M2 (see Assumptions; DESIGN D52, was Q7). The redirected request may
  carry the context headers written for the original destination, without being classified again.
- A destination matches both an internal and an external host pattern: DEST-02 (internal patterns are checked first).
- A hostname with an underscore, such as `http://orders_svc:8080/` in Docker Compose: DEST-02 (no host pattern matches, so it is `UNKNOWN`;
  the client needs a declared destination, OUT-03, to send context).
- A hostname with a trailing dot, such as `http://orders.svc.test./` (`getHost()` is `orders.svc.test.`): DEST-02, DEST-04 (no host pattern
  ends with a dot, so none matches and it is `UNKNOWN`; the client needs a declared destination, OUT-03, to send context).
- The violation mode is `report`: DIAG-02 (the call continues without the key).
- A required key is unbound, but application code set its wire header on an internal call: POL-08 (still `MISSING_REQUIRED`; the check reads the
  context, not the headers, and runs before OUT-04).
- The same, with the violation mode `report`: DIAG-02, OUT-04 (the violation is reported, the call continues, and the application's header
  is sent unchanged).
- A deadline or authority is bound during an internal call: OUT-04 (no `Boundrail-Deadline-Ms` or `Authorization` is written in M2; DL-05 and ID-03
  add them).
- An encoded value contains CR, LF or another character not allowed in an HTTP header: WIRE-08 (not sent, reported, the call continues).
- An anonymous authentication: IN-04 (not authenticated, so no contributor runs).
- A JWT claim fails to decode: DIAG-08 (boundary `INTERNAL_CALL`, no destination). A claim whose value is not a string: IN-06 (`INVALID_VALUE`).
- An inbound value is both oversized and undecodable, or an outbound value is both oversized and not a valid field value: WIRE-04 (reported once,
  as `OVERSIZED_VALUE`).
- A trusted request repeats a policy wire header: IN-02 (unbound, one `INVALID_VALUE`). An untrusted request repeats one: IN-02 (one
  `UNTRUSTED_HEADER` for the key whose wire name it is; its `key` is the policy key name, not the header name as received, DIAG-01). A request
  repeats `Boundrail-Test-Peer`: TEST-01 (`UNTRUSTED`).
- A trusted request carries a policy wire header once, with commas in its value, such as `Tenant-Id: a, b` after a proxy merged repeated
  lines: IN-02 (not a repeat; the value is decoded whole and never split, so a string codec binds `a, b`), WIRE-05 (a codec that rejects
  it leaves the key unbound, with one `INVALID_VALUE`).
- A JWT claim value longer than `wire.max-value-length`: IN-06 (no length check; WIRE-04 applies only when the value is sent on the wire).
- Two contributions for the same key: IN-05 (the first wins).
- Invalid host patterns or CIDR entries: DEST-04, IN-08 (startup fails).
- A trusted proxy rewrites the remote address from forwarded headers: IN-03 (the rewritten address is the one checked).
- The remote address is not an IP literal, such as `unknown`, or has an IPv6 zone ID: IN-08 (`UNTRUSTED`).
- The remote address is a bracketed IPv6 literal, such as `[2001:db8::1]` after Spring's forwarded-header processing: IN-08 (the one pair of
  brackets is removed and the address inside is checked). Any other use of brackets, such as `[[2001:db8::1]]` or `[2001:db8::1`, or a
  bracketed entry in `trusted-cidrs`: IN-08 (the remote address is `UNTRUSTED`; the entry fails startup with `inbound.trust=cidr`).
- The remote address is a bracketed IPv4 literal, such as `[10.0.0.1]`: IN-08 (the one pair of brackets is removed, as for IPv6, and
  `10.0.0.1` is checked).
- An entry is IPv4-compatible or IPv4-translated, such as `::10.0.0.1` or `::ffff:0:10.0.0.1`: IN-08 (a valid plain IPv6 entry, `/128` when
  bare, that never matches an IPv4 remote address; only IPv4-mapped entries are invalid).
- An IPv4-mapped address is written in hex, such as `::ffff:a00:1`: IN-08 (as a remote address it is matched as the IPv4 address `10.0.0.1`;
  as an entry it is IPv4-mapped, so it fails startup with `inbound.trust=cidr`; the strict decimal rule applies only to a dotted IPv4 part).
- An IPv4 octet or a prefix length with a leading zero, a sign or an out-of-range value, such as `010.0.0.1`, `10.0.0.256`,
  `::ffff:10.0.0.01` or `10.0.0.0/08`: IN-08 (as a remote address it is not a literal, so `UNTRUSTED`; as an entry it fails startup with
  `inbound.trust=cidr`).
- `inbound.trust` is `CIDR`, blank or `bogus`: IN-03 (relaxed binding means `cidr`; blank means `none`; `bogus` fails startup, even under
  lazy initialization).
- An untrusted request carries `Boundrail-Deadline-Ms`, or the wire header of a key that does not propagate to `INTERNAL_CALL`: IN-02 (one
  `UNTRUSTED_HEADER` each).
- Two required keys are unbound on one internal call: DIAG-02 (both reported, in key-name order; in `fail` mode the exception carries the first).
- Application code sets a forbidden header twice on an external call: WIRE-07 (one `FORBIDDEN_HEADER`).
- Application code sets a forbidden header name with no values on an external or unknown call: WIRE-07 (removed, no `FORBIDDEN_HEADER`;
  OUT-04 treats the name as unset).
- A policy uses a standard HTTP header name, such as `Accept`, `Content-Type`, `Cookie`, `Host` or `User-Agent`, as a key's wire name: POL-09
  (accepted; only `Authorization` and `Boundrail-` names are reserved). If the key does not propagate to `EXTERNAL_CALL`, WIRE-07 removes that
  header from every external or unknown call and reports one `FORBIDDEN_HEADER` for it, including headers the HTTP client sets itself, such as
  `Accept` and `Content-Type`.
- Application code sets a policy wire header whose context value would be oversized or not a valid field value, or whose key's codec
  `encode` would throw: OUT-04 (the value is not encoded, so no `OVERSIZED_VALUE` or `INVALID_VALUE` is reported and no exception is
  thrown; WIRE-04 and WIRE-08 check only values the interceptor writes).
- Application code customizes the same `RestTemplate` or `RestClient.Builder` twice: OUT-01 (the interceptor is added once, so a violation
  is reported once per request).
- An application codec's `decode` throws something other than `IllegalArgumentException`, or its `encode` throws anything: WIRE-05 (it
  propagates; no violation).
- A `null` argument or a value of the wrong type passed to the HTTP contract: CORE-13.
- Invalid configuration with `spring.main.lazy-initialization=true`: conflicting `ContextPolicy` beans, invalid host patterns, an invalid
  `inbound.trust`, invalid CIDR entries with `inbound.trust=cidr`, an invalid `violations.mode`, a negative `violations.log-interval`, a
  `wire.max-value-length` less than 1, a `spring.security.filter.order` that is not an integer or is `Integer.MIN_VALUE`, a
  `spring.security.filter.order` of `Integer.MAX_VALUE` while Spring Security is on the classpath, or several `DestinationClassifier` beans
  with none primary while HTTP client support is active, still fail startup (POL-07, DEST-04, IN-03, IN-08, DIAG-02, DIAG-03, WIRE-04,
  IN-01, IN-04, OUT-03).
- More than one `DestinationClassifier` bean: OUT-03 (the `@Primary` one is used; with none primary, startup fails whenever HTTP client support
  is active, that is `spring-web` and `spring-boot-restclient` are present, even with `spring.main.lazy-initialization=true`; without client
  support no classifier is used and startup does not fail).
- An application `DestinationClassifier` bean with an invalid pattern in `destinations.internal-hosts`: DEST-04, OUT-03 (the bean replaces the
  `HostPatternClassifier`, so `destinations.*` is not validated and startup does not fail).
- `BoundrailClients.internal(..)` or `external()` applied to a builder that is not auto-configured, such as `RestClient.builder()`: OUT-03
  (no effect, because the builder has no interceptor; the declaration is not sent and context is not propagated).
- `BoundrailClients.internal(..)` and `external()` both applied to one builder: OUT-03 (the last one applied wins).
- A `ContextPolicy` bean declared only in a parent application context: POL-07 (merged, as is a bean in any ancestor context of the context
  that runs the auto-configuration).

## Requirements *(mandatory)*

### API conventions

**CORE-13** `[M2]` CORE-03 and CORE-04 SHALL apply to the types and methods declared in [contracts/http-api.md](contracts/http-api.md) as they
do to `contracts/core-api.md`, with the same exceptions. A `null` argument, list element or varargs element, or a `null` record component passed
to a record's constructor, SHALL throw `NullPointerException` at that call; an absent `Optional` component is `Optional.empty()`, never `null`.
`ContextContributions.put` with a value that is not an instance of the key's type SHALL throw `ClassCastException` at the `put` call.

### Policy in Spring

**POL-07** `[M2]` In Spring, the effective policy SHALL be the built-in default merged with every `ContextPolicy` bean in the application context.
With no beans, the effective policy is the built-in default. `ContextPolicy` beans in the application context that runs the auto-configuration,
and in its ancestor contexts, SHALL be merged. The effective policy SHALL be built eagerly at startup, so that
conflicting `ContextPolicy` beans (POL-05) fail application startup, even when `spring.main.lazy-initialization` is `true`.

**POL-08** `[M2]` WHEN an adapter is about to cross a remote boundary (`INTERNAL_CALL`, `EXTERNAL_CALL`, `MESSAGE`) and a key that is required and
propagates to that boundary is unbound, the adapter SHALL raise a `MISSING_REQUIRED` violation (DIAG-02) before sending, one for each such
key. Required-key checks SHALL NOT be performed at in-process boundaries. Loss of context in-process surfaces at the next remote crossing.

**POL-10** `[M2]` `ContextPolicy.keys()` SHALL return an unmodifiable set of every key the policy has rules for: each key registered in it or in a
policy merged into it (POL-05), and the built-in keys (POL-06). Adapters use it to find a key by its wire name (IN-02, WIRE-07) and to find the
required keys that are unbound (POL-08). This adds a method to `ContextPolicy` in `boundrail-context-core`; its signature is in
[contracts/http-api.md](contracts/http-api.md).

### Destinations

**DEST-01** `[M2]` `Destination`, `OutboundRequest`, `DestinationClassifier` and `HostPatternClassifier` SHALL live in `boundrail-context-core` with
no dependency on any HTTP library, so that every outbound adapter (HTTP clients in v0.1; WebClient, Feign and gRPC later) classifies through the same
SPI.

**DEST-02** `[M2]` `HostPatternClassifier` SHALL apply, in order:

1. the request's declared destination, if present;
2. the internal host patterns;
3. the external host patterns;
4. otherwise, `UNKNOWN`.

Host patterns SHALL be exact hostnames or a leading `*.` wildcard matching one or more labels, compared case-insensitively. A host-pattern match SHALL
yield no service name.
A URI whose `getHost()` is `null`, because it has no host or its host is not a valid RFC 2396 name such as `orders_svc`, SHALL match no host
pattern. `HostPatternClassifier.of` SHALL copy both lists, so later changes to them have no effect on the classifier.

**DEST-03** `[M2]` Every adapter SHALL treat `UNKNOWN` exactly like `EXTERNAL`.

**DEST-04** `[M2]` IF a host pattern passed to `HostPatternClassifier.of` is not valid, THEN `of` SHALL throw `IllegalArgumentException`, so the
application fails at startup, as CORE-01 does for key names. A valid pattern is an exact name, or `*.` followed by an exact name. An exact name
is one or more labels separated by single dots, at most 253 characters long. Each label is 1 – 63 ASCII letters, digits and hyphens, and does
not start or end with a hyphen (RFC 1123 §2.1); the last label is not all digits. So `*`, `*.`, `a.*.b`, `foo*`, the empty string, a name with a
port (`orders:8080`) and a name with a trailing dot (`orders.svc.`) are invalid. IP literals are not host patterns: the last-label rule rejects
IPv4 literals and the label rule rejects IPv6 literals. DEST-02 classifies by hostname; an IPv4 pattern would also make `*.` match address
suffixes, and address ranges belong to CIDRs. A call to an IP address therefore matches no pattern and is `UNKNOWN`, unless its client declares a
destination (OUT-03). WHERE the Spring module creates the `HostPatternClassifier` bean (OUT-03), an invalid pattern in
`destinations.internal-hosts` or `destinations.external-hosts` SHALL fail application startup, even when `spring.main.lazy-initialization` is
`true`. WHERE an application `DestinationClassifier` bean replaces it, the Spring module creates no `HostPatternClassifier`, and
`destinations.*` is not validated.

### Wire format

**WIRE-01** `[M2]` Keys SHALL be carried in HTTP headers and Kafka record headers named by their wire names. Values SHALL be the codec's string
encoding, as UTF-8 bytes for Kafka. Built-in keys SHALL instead be carried as WIRE-02 defines.

**WIRE-02** `[M2]` The built-in wire formats SHALL be:

- `Boundrail-Deadline-Ms`: remaining milliseconds as a non-negative decimal integer;
- `Boundrail-Initiator`: the initiator's string value;
- authority: the standard `Authorization: Bearer <credential>` header.

The initiator has no codec: constructing it with `Initiator.of`, under ID-07's rules, is its decode step. An `IllegalArgumentException`
thrown by `Initiator.of`, for example for an empty value, is therefore a decode failure (WIRE-05).

**WIRE-04** `[M2]` IF an encoded wire value, outbound or inbound, is longer than `boundrail.context.wire.max-value-length` (default 256), counted
in UTF-16 code units as Java's `String.length()` counts them, THEN it SHALL be neither sent nor bound, and an `OVERSIZED_VALUE` violation SHALL
be reported. The operation SHALL continue. The adapter SHALL check the length first: before decoding an inbound value (WIRE-05) and before
checking the characters of an outbound value (WIRE-08). A value that is oversized and also invalid SHALL therefore be reported once, as
`OVERSIZED_VALUE`. This is the only length limit; codecs (WIRE-03) stay limit-free, and values from authentication, such as JWT claims
(IN-06), are not wire values (DESIGN D35). An outbound value is checked only when the adapter is about to write it: a header value set by
application code is not an encoded wire value, and is not checked (OUT-04). The `Authorization` value ID-03 `[M5]` writes is not checked
either (DESIGN D60). IF `wire.max-value-length` is less than 1, THEN application
startup SHALL fail, even when `spring.main.lazy-initialization` is `true`.

**WIRE-05** `[M2]` IF a trusted inbound value fails to decode, THEN the key SHALL be left unbound and an `INVALID_VALUE` violation SHALL be reported.
The request SHALL continue. The report and its log entry SHALL follow DIAG-09: they contain neither the value nor the codec's exception message.
A value fails to decode only when the codec's `decode` throws `IllegalArgumentException` or a subclass of it, the rejection WIRE-03 defines.
For `Boundrail-Initiator`, `decode` is `Initiator.of` (WIRE-02).
Any other exception thrown by a codec's `decode` SHALL propagate unchanged, and no violation SHALL be reported for it: it is a bug in the codec,
not an invalid value. Only `decode` has a defined rejection: any exception thrown by a codec's `encode`, including an
`IllegalArgumentException`, SHALL propagate unchanged, and no violation SHALL be reported for it.

**WIRE-06** `[M2]` Wire names SHALL be matched case-insensitively for HTTP and exactly for Kafka.

**WIRE-07** `[M2]` WHEN sending to an `EXTERNAL` or `UNKNOWN` destination, the adapter SHALL remove every header whose name is a policy wire name of a
key that does not propagate to `EXTERNAL_CALL`, even if application code set it. It SHALL report one `FORBIDDEN_HEADER` violation per removed
header name that has at least one value, however many values application code set under that name (names compared as WIRE-06 defines). IF a
removed header name has no values, THEN no violation SHALL be reported for it: nothing could leak, and OUT-04 treats such a name as unset. The
`Authorization` header SHALL NOT be removed. A header set by application code whose key propagates to `EXTERNAL_CALL` is kept unchanged (OUT-04).
Note: POL-09 does not reserve standard HTTP header names, so a policy may use `Accept` or `Content-Type` as a wire name. If that key does
not propagate to `EXTERNAL_CALL`, this rule removes that header from every external or unknown call and reports it, including when the HTTP
client set it.

**WIRE-08** `[M2]` IF a value the adapter is about to write to an HTTP header (WIRE-01, WIRE-02) is not a valid field value as defined here,
THEN the adapter SHALL NOT write it, SHALL report an `INVALID_VALUE` violation, and the call SHALL continue. A valid field value is empty, or
starts and ends with a visible US-ASCII character (U+0021 – U+007E) and contains only visible US-ASCII characters, spaces and horizontal tabs.
This is RFC 9110 §5.5's field value without `obs-text`: CR, LF, other control characters, and every character above U+007E, including
U+0080 – U+00FF, are invalid. A value that is also oversized is reported as WIRE-04 defines. This follows DIAG-02 and DESIGN §10, where only
`MISSING_REQUIRED` can fail an operation, and treats invalid values the way WIRE-04 treats oversized ones. A header value set by application
code is not written by the adapter, and is not checked (OUT-04).

### Inbound HTTP

**IN-01** `[M2]` The Spring module SHALL register `ContextInboundFilter` before Spring Security's filter chain, at whatever
`spring.security.filter.order` is configured: the filter's order SHALL be that property's value minus one, so -101 with the property's
default of -100. IF `spring.security.filter.order` is not an integer, or is `Integer.MIN_VALUE`, at which no order is one below it,
THEN application startup SHALL fail, even when `spring.main.lazy-initialization` is `true`. It SHALL be around-style: it SHALL run the
rest of the chain inside a scope holding the keys it binds (none, if it binds none). WHEN it binds no keys, it SHALL run the rest of the
chain in the caller's existing scope, and SHALL NOT enter an empty scope.

**IN-02** `[M2]` WHEN a request arrives, `ContextInboundFilter` SHALL ask the `InboundTrustVerifier` for a verdict. WHEN the verdict is
`TRUSTED_INTERNAL`, it SHALL decode and bind every key whose policy includes `INTERNAL_CALL` and whose wire header is present, except `AUTHORITY` and
`DEADLINE` (which DL-03 handles). A wire header whose value is the empty string `""` is present, and that value SHALL be decoded, as OUT-04
treats `""` as a value. WHEN the verdict is `UNTRUSTED`, it SHALL bind none of them, and SHALL report one `UNTRUSTED_HEADER` violation per
policy wire header name present, however many times that header appears (names compared as WIRE-06 defines). The policy wire header names are
the wire names of every key in the effective policy's `keys()` (POL-10): the built-in keys' wire names, such as `Boundrail-Deadline-Ms`, and
the wire names of keys that do not propagate to `INTERNAL_CALL` are included, not only those of the keys the trusted path would bind.
`Authorization` is not one, because `AUTHORITY` has no wire name (POL-03). Untrusted headers SHALL never fail the request. IF a trusted request
carries more than once the wire header of a key the trusted path would bind (names compared as WIRE-06 defines), THEN the filter SHALL leave
that key unbound, SHALL decode none of its values, and SHALL report one `INVALID_VALUE` violation. A header is carried more than once only
when it arrives as more than one header line. A single header line is one value, even if it contains commas, such as `Tenant-Id: a, b` after
a proxy has merged repeated lines (RFC 9110 §5.3). The filter SHALL decode that value as a whole and SHALL NOT split it on commas; the key's
codec decides whether it is valid, so a string codec binds `a, b`, and a codec that rejects it leaves the key unbound with an `INVALID_VALUE`
violation (WIRE-05).

**IN-03** `[M2]` The default verifier SHALL return `UNTRUSTED` for every request. WHERE `boundrail.context.inbound.trust=cidr`, the built-in CIDR
verifier SHALL return `TRUSTED_INTERNAL` exactly when the request's remote address, `HttpServletRequest.getRemoteAddr()`, is inside one of
`boundrail.context.inbound.trusted-cidrs` (IN-08). The value of `boundrail.context.inbound.trust` SHALL be matched with Spring Boot's relaxed
enum binding (ignoring case, surrounding whitespace and non-alphanumeric characters), so `CIDR` and ` Cidr ` mean `cidr`, and an empty or blank
value SHALL mean `none`. IF the value matches neither `none` nor `cidr`, such as `bogus`, THEN application startup SHALL fail, even when
`spring.main.lazy-initialization` is `true`. A bean of type `InboundTrustVerifier` SHALL replace the built-in verifier. The checked address
is the one the servlet container reports. Where forwarded-header processing is enabled (for example `server.forward-headers-strategy` or a
`ForwardedHeaderFilter`), it is taken from the `Forwarded` or `X-Forwarded-For` header, so a proxy, or a client that forges those headers when
no proxy strips them, decides which address is checked. IF the remote address, once one enclosing pair of square brackets is removed, is not
an IP address literal (for example `unknown`, taken from a forwarded header), or is an IPv6 literal with a zone ID, THEN the CIDR verifier
SHALL return `UNTRUSTED`, as IN-08 defines. The
description of `inbound.trusted-cidrs` in the Configuration table below SHALL state how forwarded headers decide the checked address. That
table is all M2 owes; the user-facing configuration reference is built from it by REL-03 `[M7]`.

**IN-04** `[M2]` WHERE Spring Security is on the classpath, the Spring module SHALL register `ContextAuthenticationFilter` after Spring Security's
authentication filters, at whatever `spring.security.filter.order` is configured: the filter's order SHALL be that property's value plus one,
so -99 with the property's default of -100. IF `spring.security.filter.order` is `Integer.MAX_VALUE`, at which no order is one above it, THEN
application startup SHALL fail, even when `spring.main.lazy-initialization` is `true`. WHEN the request is authenticated, it SHALL invoke
every `ContextContributor` bean and run the rest of the chain in a nested scope that binds the contributed values. The request is
authenticated when the `SecurityContext` holds an `Authentication` that is not `null`, reports `isAuthenticated()`, and is not anonymous, as
Spring Security's `AuthenticationTrustResolver.isAuthenticated` defines it; an `AnonymousAuthenticationToken` is therefore not authenticated.
Otherwise the filter SHALL invoke no contributor and SHALL run the rest of the chain in the current scope. An exception thrown by the rest of
the chain SHALL propagate from the filter unchanged, including a checked exception the filter does not declare.

**IN-05** `[M2]` Contributed values SHALL bind only keys that are not already bound (trusted headers take precedence, DESIGN D27). The exception for
`AUTHORITY`, which is always bound from the authentication, is delivered with ID-02 `[M5]`. The same rule applies between contributions: IF more
than one contribution names the same key, from two contributors or from one contributor twice, THEN the first SHALL be bound and the later ones
SHALL be ignored. Contributors SHALL be invoked in bean order (`@Order` or `Ordered`).

**IN-06** `[M2]` `JwtClaimContributor` SHALL decode the named claim of a JWT authentication with the given codec. IF the claim is missing, THEN the
key SHALL remain unbound. IF the claim's value is not a `String` (for example a number, a boolean or a list; it is not converted to a string), or
fails to decode (WIRE-05), THEN the key SHALL remain unbound and the contributor SHALL call `ContextContributions.invalid(key)`, which reports
`INVALID_VALUE` (IN-07, DIAG-09). WIRE-04's length limit SHALL NOT apply to a claim value: it comes from verified authentication, not from the
wire. IF a bound claim value is later sent on the wire, THEN WIRE-04 SHALL apply to its encoding at that point.

**IN-07** `[M2]` WHEN a contributor calls `ContextContributions.invalid(key)`, `ContextAuthenticationFilter` SHALL report one `INVALID_VALUE`
violation for that key, with the boundary and destination DIAG-08 defines. `invalid` SHALL bind nothing, and SHALL NOT affect any contribution
for the same key.

**IN-08** `[M2]` Each entry of `boundrail.context.inbound.trusted-cidrs` SHALL be an IPv4 or IPv6 address literal followed by `/` and a prefix
length (0 – 32 for IPv4, 0 – 128 for IPv6), or a bare address literal, which means `/32` or `/128`. An IPv4 remote address SHALL match only IPv4
entries and an IPv6 remote address only IPv6 entries, except that an IPv4-mapped IPv6 remote address (`::ffff:a.b.c.d`) SHALL be matched as the
IPv4 address `a.b.c.d`. An IPv6 literal is IPv4-mapped when its value is in `::ffff:0:0/96`, whether its last 32 bits are written as a dotted
IPv4 part (`::ffff:10.0.0.1`) or in hex (`::ffff:a00:1`, matched as `10.0.0.1`). IPv4-compatible and IPv4-translated literals, such as
`::10.0.0.1` and `::ffff:0:10.0.0.1`, are not IPv4-mapped: they are plain IPv6 literals, so as entries they are valid IPv6 entries (`/128` when
bare) and never match an IPv4 remote address. An IPv4 literal SHALL be four decimal octets separated by dots, each 0 – 255 with no leading zeros
(`10.0.0.1`, not `010.0.0.1` or `10.0.0.256`); this SHALL also apply to a dotted IPv4 part of an IPv6 literal, such as `::ffff:a.b.c.d` or
`::a.b.c.d`, and not to hex groups. A prefix length SHALL be plain decimal digits with no sign and no leading zeros (`/8`, not `/08` or `/+8`).
Entries are strict: an entry with host bits set beyond its prefix length (`10.0.0.1/8`), an IPv4-mapped IPv6 entry in any form
(`::ffff:10.0.0.0/104`, `::ffff:10.0.0.1`, `::ffff:a00:1`), an entry with an IPv6 zone ID (`fe80::1%eth0`), an entry in square brackets
(`[2001:db8::1]`), an entry that breaks the literal or prefix form above and a hostname are not valid. WHERE `inbound.trust=cidr`, IF an entry
is not valid, THEN the application SHALL fail at startup, even when `spring.main.lazy-initialization` is `true`. Entries SHALL be parsed without
DNS lookups.
IF the remote address is enclosed in exactly one pair of square brackets, with no other bracket inside, such as `[2001:db8::1]` (Spring's
forwarded-header processing produces this form) or `[10.0.0.1]`, THEN the verifier SHALL remove that pair and check the address inside, whether
it is IPv4 or IPv6 (`[10.0.0.1]` is checked as `10.0.0.1`). IF the remote address, after that, is not an IPv4 or IPv6 address literal in the
form above, contains a square bracket, or is an IPv6 literal with a zone ID (`fe80::1%eth0`), THEN it SHALL match no entry, and the verdict
SHALL be `UNTRUSTED`, failing closed. The remote address SHALL also be parsed without DNS lookups.

### Outbound HTTP

**OUT-01** `[M2]` The Spring module SHALL add the library's `ClientHttpRequestInterceptor` to every auto-configured `RestClient.Builder` and
`RestTemplateBuilder`. The interceptor SHALL be around-style (`execution.execute`). The interceptor SHALL be added at most once per client:
IF a `RestTemplate` or `RestClient.Builder` already has the library's interceptor, for example because application code customized the same
template or builder twice, THEN the customizer SHALL NOT add another, so that a violation is never reported twice for one request.

**OUT-02** `[M2]` WHEN a request is about to be sent, the interceptor SHALL build an `OutboundRequest` from the request URI and the client's
declaration, if any, and classify it with the effective `DestinationClassifier` (DEST-02, DEST-03).

**OUT-03** `[M2]` The Spring module SHALL provide a `HostPatternClassifier` bean configured from `boundrail.context.destinations.internal-hosts` and
`boundrail.context.destinations.external-hosts`. A bean of type `DestinationClassifier` SHALL replace it. IF the application context has more
than one `DestinationClassifier` bean, THEN the `@Primary` one SHALL be used. WHERE the Spring module's HTTP client support is active
(`spring-web` and `spring-boot-restclient` are on the classpath), IF none of them is primary, THEN application startup SHALL fail, even when
`spring.main.lazy-initialization` is `true`. Without client support no classifier is used, and several `DestinationClassifier` beans with none
primary SHALL NOT fail startup.
`BoundrailClients.internal(service)` SHALL declare `Destination(INTERNAL, service)`, and `BoundrailClients.external()` SHALL declare
`Destination(EXTERNAL, empty)`, for every request made through the client it configures. IF both are applied to one builder, THEN the last one
applied SHALL win. The declaration is read by the library's interceptor (OUT-01, OUT-02): applied to a `RestClient.Builder` that has no such
interceptor, because it is not auto-configured (for example `RestClient.builder()`), `internal` and `external` SHALL have no effect.

**OUT-04** `[M2]` For `INTERNAL` destinations, the interceptor SHALL write the wire headers of bound keys that propagate to `INTERNAL_CALL`, after the
required-key check (POL-08), except `AUTHORITY` and `DEADLINE`: `AUTHORITY` has no wire name (POL-03) and is carried by ID-03 `[M5]`, and
`Boundrail-Deadline-Ms` is written by DL-05 `[M4]`. For `EXTERNAL` and `UNKNOWN` destinations, it SHALL write only keys that propagate to
`EXTERNAL_CALL` (none by default), and SHALL apply WIRE-07. IF application code has already set a policy wire header (IN-02) on a request,
whatever its destination (`INTERNAL`, `EXTERNAL` or `UNKNOWN`), THEN the interceptor SHALL leave it unchanged and SHALL NOT write the context
value under that name, as ID-03 does for `Authorization`; the one exception is `Boundrail-Deadline-Ms` on an `INTERNAL` request while a
deadline is bound, which DL-05 `[M4]` replaces. On an `EXTERNAL` or `UNKNOWN` request, WIRE-07 still removes such a header when its
key does not propagate to `EXTERNAL_CALL`; for a key that does, the application's value is sent. A header counts as already set only when it
has at least one value (names compared as WIRE-06 defines): a name present with no values SHALL be treated as unset, so the interceptor
writes the context value under it exactly as it would if the name were absent. The empty string `""` is a value, so a header whose only
value is `""` is set. For a key whose wire header is already set, the interceptor SHALL NOT encode the context value, and SHALL run neither
the WIRE-04 length check nor the WIRE-08 field-value check on it: it reports no `OVERSIZED_VALUE` or `INVALID_VALUE` for that key, and that
key's `encode` is not called, so it cannot throw. Any exception thrown by a codec's `encode`, including an `IllegalArgumentException`, SHALL
propagate unchanged, and no violation SHALL be reported for it (WIRE-05).

### Violations

**DIAG-01** `[M2]` The library SHALL recognise exactly the violation kinds listed in [contracts/http-api.md](contracts/http-api.md).
A `Violation`'s `key` SHALL be the name of the policy key it concerns, such as `tenant`, for every kind, including `UNTRUSTED_HEADER` and
`FORBIDDEN_HEADER`. It SHALL NOT be the header name as received or as set by application code: HTTP header names are case-insensitive (WIRE-06),
and the log entry reports the wire name separately (DIAG-09).

**DIAG-02** `[M2]` WHEN a `MISSING_REQUIRED` violation occurs and `boundrail.context.violations.mode=fail` (default), the adapter SHALL report the
violation (DIAG-03) and then throw a `ContextViolationException` carrying it, before crossing the boundary. WHEN the mode is `report`, it SHALL
report the violation and continue without the key. WHEN more than one required key is unbound on one crossing (POL-08), the adapter SHALL
report one `MISSING_REQUIRED` violation per key, in ascending order of key name as compared by `String.compareTo` (key names are unique in a
policy, POL-05), and in `fail` mode SHALL then throw one `ContextViolationException` carrying the first of them. All other violation kinds SHALL
always be report-only. The mode SHALL be matched with Spring Boot's relaxed enum binding (ignoring case, surrounding whitespace and
non-alphanumeric characters), so `Report`, `re-port` and `RE_PORT` mean `report`, and an empty or blank value SHALL mean `fail`. IF the mode
matches neither value, such as `bogus`, THEN application startup SHALL fail, even when `spring.main.lazy-initialization` is `true`.

**DIAG-03** `[M2]` Reporting a violation SHALL:

- log at WARN, rate-limited to one entry per (kind, key, boundary) per `boundrail.context.violations.log-interval` (default 1 minute); WHEN the
  interval is zero, every occurrence SHALL be logged, with no rate limit;
- WHERE the application context has a unique or primary `MeterRegistry` bean, increment the counter `boundrail.context.violations` in that
  registry, tagged with `kind`, `key` and `boundary` in lowercase.

Every occurrence SHALL be counted, even when the log entry is rate-limited. The library SHALL NOT count violations in Micrometer's global registry
(`Metrics.globalRegistry`), because violation reporting belongs to one application context (DESIGN D31, §11). IF `log-interval` is negative,
THEN application startup SHALL fail, even when `spring.main.lazy-initialization` is `true`.

**DIAG-08** `[M2]` For a violation found on an inbound value, `boundary` SHALL be the boundary the value travelled over (`INTERNAL_CALL` for HTTP,
`MESSAGE` for Kafka), and `destination` SHALL be empty. A value taken from the authentication of an HTTP request, such as a JWT claim (IN-06), is an
inbound HTTP value.

**DIAG-09** `[M2]` Neither a `Violation` nor its log entry SHALL contain the value it concerns, whether raw, encoded or decoded, nor the message
or cause of an exception thrown by a codec. The log entry SHALL identify the value only by its key name, its wire name where the key has one,
and the boundary. Codec messages echo the raw input, which may be untrusted (principle III), unbounded, contain CR or LF, or belong to a key that
is not loggable (DESIGN D35). IT-2.8 verifies the log entries and the counter tags; the unit tests of WIRE-04 and WIRE-05 verify the
`Violation` records, which the test topology does not expose.

### Test support

**TEST-01** `[M2]` `boundrail-context-test` SHALL provide `TestInboundTrustVerifier`, an `InboundTrustVerifier` with a public no-argument
constructor. It SHALL return `TRUSTED_INTERNAL` exactly when the request carries the header `Boundrail-Test-Peer` exactly once, with a secret
generated randomly once per JVM and shared by every instance; a request that carries it more than once is `UNTRUSTED`. Its static method
`peer()` SHALL return a `Consumer<RestClient.Builder>` that adds that header, with that secret, to every request of the client it configures.
It is applied per client, so a test applies it to internal clients only and the secret never reaches an external stub. This simulates mesh
identity in tests. Neither the verifier nor `peer()` SHALL be auto-configured. Because `InboundTrustVerifier` is part of its public API,
`boundrail-context-test` SHALL declare `boundrail-context-spring` as an `api` dependency, so that consumers receive it transitively. It SHALL
declare `spring-web` and `jakarta.servlet-api` as `compileOnly`: only `peer()` uses `spring-web`, and an application that calls it already has
it; only a servlet container calls `verify`, and any servlet application provides the servlet API (DESIGN D37). BuildSmokeIT checks the
generated POM and Gradle module metadata of `boundrail-context-test`. In them, `boundrail-context-spring` SHALL appear as a compile/api
dependency, and `spring-web` and `jakarta.servlet-api` SHALL NOT appear.

### Configuration

Properties use the prefix `boundrail.context.`.

Every startup failure caused by invalid configuration also occurs when `spring.main.lazy-initialization` is `true`: conflicting `ContextPolicy`
beans (POL-07), invalid host patterns (DEST-04), an invalid `inbound.trust` (IN-03), invalid CIDR entries with `inbound.trust=cidr` (IN-08),
an invalid `violations.mode` (DIAG-02), a negative
`violations.log-interval` (DIAG-03), a `wire.max-value-length` less than 1 (WIRE-04), a `spring.security.filter.order` that is not an
integer or is `Integer.MIN_VALUE` (IN-01), a `spring.security.filter.order` of `Integer.MAX_VALUE` while Spring Security is on the classpath
(IN-04), and, while HTTP client support is active (`spring-web` and `spring-boot-restclient` are
present), several `DestinationClassifier` beans with none primary (OUT-03).

| Property | Default | Meaning |
|---|---|---|
| `violations.mode` | `fail` | `fail` or `report`, matched with Spring Boot's relaxed enum binding (ignoring case, surrounding whitespace and non-alphanumeric characters); an empty or blank value means `fail`, and any value that matches neither, such as `bogus`, fails startup, even under lazy initialization (DIAG-02) |
| `violations.log-interval` | `1m` | Rate limit for violation logs; `0s` logs every occurrence, and a negative value fails startup (DIAG-03) |
| `inbound.trust` | `none` | `none` or `cidr`, matched with Spring Boot's relaxed enum binding (ignoring case, surrounding whitespace and non-alphanumeric characters); an empty or blank value means `none`, and any value that matches neither, such as `bogus`, fails startup, even under lazy initialization; a verifier bean overrides it (IN-03) |
| `inbound.trusted-cidrs` | `[]` | IPv4 or IPv6 CIDRs, or bare addresses, trusted when `inbound.trust=cidr` (IN-03, IN-08). IPv4 octets and prefix lengths are plain decimal with no leading zeros, and entries are never in square brackets; with `inbound.trust=cidr`, an invalid entry fails startup. Matched against `getRemoteAddr()`, after removing one enclosing pair of square brackets (`[2001:db8::1]`); a remote address that is not a literal in that form is untrusted. With forwarded-header processing enabled, a proxy, or a client forging `Forwarded` or `X-Forwarded-For` headers that no proxy strips, decides that address |
| `destinations.internal-hosts` | `[]` | Host patterns classified as internal (DEST-02, OUT-03) |
| `destinations.external-hosts` | `[]` | Host patterns classified as external (DEST-02, OUT-03) |
| `wire.max-value-length` | `256` | Maximum encoded wire value length in UTF-16 code units (`String.length()`), checked before decoding or character checks; not applied to JWT claims; a value less than 1 fails startup (WIRE-04, IN-06) |

### Key Entities

- **Destination**: the classified target of an outbound call (`INTERNAL`, `EXTERNAL`, `UNKNOWN`) plus an optional service name.
- **Trust verdict**: `TRUSTED_INTERNAL` or `UNTRUSTED` for an inbound caller.
- **Violation**: a context rule broken at runtime, with only observed evidence.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: IT-2.1 – IT-2.9 are enabled and green on JDK 21 and JDK 25.
- **SC-002**: With default configuration, no context header reaches the `partner` stub in any scenario.
- **SC-003**: No untrusted, invalid or oversized header fails a request, except when an application codec throws something other than
  `IllegalArgumentException` from `decode`, which is an application bug and propagates (WIRE-05).

## Assumptions

- 002-core-runtime is complete.
- Only servlet-based Spring MVC, `RestClient` and `RestTemplate` are covered; WebFlux and WebClient are out of scope for v0.1.
- Redirects are a known limitation in M2. The interceptor runs once per request (OUT-01, OUT-02). An HTTP client that follows a 3xx redirect
  does so below the interceptor, so a request redirected to another host may carry the context headers written for the original destination,
  without being classified again or stripped (WIRE-07). Turn off redirect following on clients declared internal (OUT-03), and on clients that
  reach hosts matched by an internal host pattern (DEST-02). DESIGN D52 (was Q7) keeps this limitation for v0.1, documented in the Javadoc
  of `BoundrailClients` (OUT-05 in [005-deadlines](../005-deadlines/spec.md)).
- Spring MVC async processing is a known limitation in M2. `ContextInboundFilter` does not run again on async dispatches, so with
  `DeferredResult` or `Callable` the async dispatch runs without the inbound context that IN-01 and IN-02 bind for the original request.
  DESIGN Q8 tracks the fix, picked up in [004-async-diagnostics](../004-async-diagnostics/spec.md) `[M3]`.
