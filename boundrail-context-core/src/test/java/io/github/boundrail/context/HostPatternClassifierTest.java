package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class HostPatternClassifierTest {

    private static final HostPatternClassifier CLASSIFIER = HostPatternClassifier.of(
            List.of("orders.svc.test", "shared.example.com"),
            List.of("partner.example.com", "shared.example.com"));

    private static OutboundRequest undeclared(String uri) {
        return new OutboundRequest(URI.create(uri), Optional.empty());
    }

    @Test
    @DisplayName("DEST-02 a declared destination wins over host patterns")
    void aDeclaredDestinationWinsOverHostPatterns() {
        Destination internalOrders = new Destination(Destination.Kind.INTERNAL, Optional.of("orders"));
        Destination external = new Destination(Destination.Kind.EXTERNAL, Optional.empty());

        Destination toExternalHost = CLASSIFIER.classify(
                new OutboundRequest(URI.create("http://partner.example.com/x"), Optional.of(internalOrders)));
        Destination toInternalHost = CLASSIFIER.classify(
                new OutboundRequest(URI.create("http://orders.svc.test/x"), Optional.of(external)));

        assertThat(toExternalHost).isEqualTo(new Destination(Destination.Kind.INTERNAL, Optional.of("orders")));
        assertThat(toInternalHost).isEqualTo(new Destination(Destination.Kind.EXTERNAL, Optional.empty()));
    }

    @Test
    @DisplayName("DEST-02 internal patterns are checked before external patterns")
    void internalPatternsAreCheckedBeforeExternalPatterns() {
        assertThat(CLASSIFIER.classify(undeclared("http://shared.example.com/x")))
                .extracting(Destination::kind)
                .isEqualTo(Destination.Kind.INTERNAL);
    }

    @Test
    @DisplayName("DEST-02 an external pattern match is EXTERNAL")
    void anExternalPatternMatchIsExternal() {
        assertThat(CLASSIFIER.classify(undeclared("https://partner.example.com/x")))
                .extracting(Destination::kind)
                .isEqualTo(Destination.Kind.EXTERNAL);
    }

    @Test
    @DisplayName("DEST-02 a host matching no pattern is UNKNOWN")
    void aHostMatchingNoPatternIsUnknown() {
        assertThat(CLASSIFIER.classify(undeclared("http://other.example.com/x")))
                .isEqualTo(new Destination(Destination.Kind.UNKNOWN, Optional.empty()));
        assertThat(CLASSIFIER.classify(undeclared("mailto:ops@example.com")))
                .isEqualTo(new Destination(Destination.Kind.UNKNOWN, Optional.empty()));
        assertThat(CLASSIFIER.classify(undeclared("/relative/path")))
                .isEqualTo(new Destination(Destination.Kind.UNKNOWN, Optional.empty()));
    }

    @Test
    @DisplayName("DEST-02 a host-pattern match yields no service name")
    void aHostPatternMatchYieldsNoServiceName() {
        assertThat(CLASSIFIER.classify(undeclared("http://orders.svc.test/x")))
                .isEqualTo(new Destination(Destination.Kind.INTERNAL, Optional.empty()));
        assertThat(CLASSIFIER.classify(undeclared("http://partner.example.com/x")))
                .isEqualTo(new Destination(Destination.Kind.EXTERNAL, Optional.empty()));
    }

    @Test
    @DisplayName("DEST-02 a URI whose host does not parse is UNKNOWN")
    void aUriWhoseHostDoesNotParseIsUnknown() {
        HostPatternClassifier classifier = HostPatternClassifier.of(List.of("*.svc.test"), List.of("*.svc.test"));

        assertThat(classifier.classify(undeclared("http://orders_x.svc.test:8080/")))
                .isEqualTo(new Destination(Destination.Kind.UNKNOWN, Optional.empty()));
    }

    @Test
    @DisplayName("DEST-02 host patterns compare case-insensitively")
    void hostPatternsCompareCaseInsensitively() {
        HostPatternClassifier classifier = HostPatternClassifier.of(
                List.of("orders.svc.test", "*.MESH.Test"),
                List.of("PARTNER.Example.COM"));

        assertThat(classifier.classify(undeclared("http://Orders.SVC.test/x")))
                .isEqualTo(new Destination(Destination.Kind.INTERNAL, Optional.empty()));
        assertThat(classifier.classify(undeclared("http://partner.example.com/x")))
                .isEqualTo(new Destination(Destination.Kind.EXTERNAL, Optional.empty()));
        assertThat(classifier.classify(undeclared("http://billing.mesh.test/x")))
                .isEqualTo(new Destination(Destination.Kind.INTERNAL, Optional.empty()));
    }

    @Test
    @DisplayName("DEST-02 a leading *. wildcard matches one or more labels")
    void aLeadingWildcardMatchesOneOrMoreLabels() {
        HostPatternClassifier classifier = HostPatternClassifier.of(List.of("*.svc.test"), List.of("*.partner.test"));

        assertThat(classifier.classify(undeclared("http://a.svc.test/x")))
                .isEqualTo(new Destination(Destination.Kind.INTERNAL, Optional.empty()));
        assertThat(classifier.classify(undeclared("http://a.b.svc.test/x")))
                .isEqualTo(new Destination(Destination.Kind.INTERNAL, Optional.empty()));
        assertThat(classifier.classify(undeclared("http://api.partner.test/x")))
                .isEqualTo(new Destination(Destination.Kind.EXTERNAL, Optional.empty()));
    }

    @Test
    @DisplayName("DEST-02 a wildcard does not match the bare suffix")
    void aWildcardDoesNotMatchTheBareSuffix() {
        HostPatternClassifier classifier = HostPatternClassifier.of(List.of("*.svc.test"), List.of());

        assertThat(classifier.classify(undeclared("http://svc.test/x")))
                .isEqualTo(new Destination(Destination.Kind.UNKNOWN, Optional.empty()));
        assertThat(classifier.classify(undeclared("http://xsvc.test/x")))
                .isEqualTo(new Destination(Destination.Kind.UNKNOWN, Optional.empty()));
    }

    @Test
    @DisplayName("DEST-02 of copies its lists")
    void ofCopiesItsLists() {
        List<String> internal = new ArrayList<>(List.of("orders.svc.test"));
        List<String> external = new ArrayList<>(List.of("partner.example.com"));
        HostPatternClassifier classifier = HostPatternClassifier.of(internal, external);

        internal.set(0, "partner.example.com");
        external.set(0, "orders.svc.test");

        assertThat(classifier.classify(undeclared("http://orders.svc.test/x")))
                .extracting(Destination::kind)
                .isEqualTo(Destination.Kind.INTERNAL);
        assertThat(classifier.classify(undeclared("http://partner.example.com/x")))
                .extracting(Destination::kind)
                .isEqualTo(Destination.Kind.EXTERNAL);
    }

    @Test
    @DisplayName("DEST-01 destination types depend on no HTTP library")
    void destinationTypesDependOnNoHttpLibrary() {
        List<Type> named = new ArrayList<>();
        for (Class<?> type : List.of(Destination.class, OutboundRequest.class, DestinationClassifier.class, HostPatternClassifier.class)) {
            for (Field field : type.getDeclaredFields()) {
                named.add(field.getGenericType());
            }
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                named.addAll(Arrays.asList(constructor.getGenericParameterTypes()));
                named.addAll(Arrays.asList(constructor.getGenericExceptionTypes()));
            }
            for (Method method : type.getDeclaredMethods()) {
                named.add(method.getGenericReturnType());
                named.addAll(Arrays.asList(method.getGenericParameterTypes()));
                named.addAll(Arrays.asList(method.getGenericExceptionTypes()));
            }
        }

        assertThat(named.stream().flatMap(HostPatternClassifierTest::classesIn))
                .isNotEmpty()
                .allSatisfy(type -> assertThat(type.getName())
                        .matches("java\\..*|io\\.github\\.boundrail\\.context\\..*")
                        .doesNotStartWith("java.net.http.")
                        .doesNotStartWith("com.sun.net.httpserver."));
    }

    /** The classes a possibly generic type names, with arrays unwrapped and primitives dropped. */
    private static Stream<Class<?>> classesIn(Type type) {
        return switch (type) {
            case Class<?> c when c.isArray() -> classesIn(c.getComponentType());
            case Class<?> c when c.isPrimitive() -> Stream.empty();
            case Class<?> c -> Stream.of(c);
            case ParameterizedType p -> Stream.concat(
                    classesIn(p.getRawType()),
                    Arrays.stream(p.getActualTypeArguments()).flatMap(HostPatternClassifierTest::classesIn));
            case GenericArrayType a -> classesIn(a.getGenericComponentType());
            case WildcardType w -> Stream.concat(Arrays.stream(w.getUpperBounds()), Arrays.stream(w.getLowerBounds()))
                    .flatMap(HostPatternClassifierTest::classesIn);
            case TypeVariable<?> v -> Arrays.stream(v.getBounds()).flatMap(HostPatternClassifierTest::classesIn);
            default -> throw new IllegalStateException("Unexpected type " + type);
        };
    }

    @Test
    @DisplayName("CORE-13 destination types reject null")
    void destinationTypesRejectNull() {
        URI uri = URI.create("http://orders.svc.test/x");
        List<String> withNull = Arrays.asList("orders.svc.test", null);

        assertThatNullPointerException().isThrownBy(() -> new Destination(null, Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> new Destination(Destination.Kind.INTERNAL, null));
        assertThatNullPointerException().isThrownBy(() -> new OutboundRequest(null, Optional.empty()));
        assertThatNullPointerException().isThrownBy(() -> new OutboundRequest(uri, null));
        assertThatNullPointerException().isThrownBy(() -> HostPatternClassifier.of(null, List.of()));
        assertThatNullPointerException().isThrownBy(() -> HostPatternClassifier.of(List.of(), null));
        assertThatNullPointerException().isThrownBy(() -> HostPatternClassifier.of(withNull, List.of()));
        assertThatNullPointerException().isThrownBy(() -> HostPatternClassifier.of(List.of(), withNull));
        assertThatNullPointerException().isThrownBy(() -> CLASSIFIER.classify(null));
    }

    /** A 253-character name: three 63-character labels and a 61-character label, joined by three dots. */
    private static final String NAME_253 = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);

    /** A 254-character name: three 63-character labels and a 62-character label, joined by three dots. */
    private static final String NAME_254 = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(62);

    static Stream<String> invalidHostPatterns() {
        return Stream.of("*", "*.", "a.*.b", "foo*", "", "orders:8080", "orders.svc.", "a..b", ".a", "-a.b", "a-.b",
                "a".repeat(64), NAME_254, "10.0.0.1", "*.10.0.0.1", "::1", "[::1]");
    }

    @ParameterizedTest
    @MethodSource("invalidHostPatterns")
    @DisplayName("DEST-04 invalid host patterns are rejected")
    void invalidHostPatternsAreRejected(String pattern) {
        assertThatIllegalArgumentException().isThrownBy(() -> HostPatternClassifier.of(List.of(pattern), List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> HostPatternClassifier.of(List.of(), List.of(pattern)));
    }

    static Stream<String> validHostPatterns() {
        return Stream.of("orders", "orders.svc.test", "*.svc.test", "ORDERS.svc.test", "a-b.c", "123.example", "a".repeat(63), NAME_253,
                "*." + NAME_253);
    }

    @ParameterizedTest
    @MethodSource("validHostPatterns")
    @DisplayName("DEST-04 valid host patterns are accepted")
    void validHostPatternsAreAccepted(String pattern) {
        assertThatCode(() -> HostPatternClassifier.of(List.of(pattern), List.of(pattern))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CORE-13 a null pattern list is rejected before pattern validation")
    void aNullPatternListIsRejectedBeforePatternValidation() {
        assertThatNullPointerException().isThrownBy(() -> HostPatternClassifier.of(List.of("*"), null));
    }
}
