package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ContextKeyTest {

    @ParameterizedTest
    @ValueSource(strings = {"tenant", "x9", "tenant-id", "acme.tenant-id", "a.b1.c-2", "boundrail"})
    @DisplayName("CORE-01 accepts names matching the key name pattern")
    void acceptsNamesMatchingTheKeyNamePattern(String name) {
        assertThat(ContextKey.of(name, String.class).name()).isEqualTo(name);
        assertThat(ContextKey.of(name, String.class, "d").name()).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Tenant", "tenantId", "1tenant", "tenant.", "acme..tenant", ".tenant", "acme.1x", "tenant_id"})
    @DisplayName("CORE-01 rejects invalid names")
    void rejectsInvalidNames(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> ContextKey.of(name, String.class));
        assertThatIllegalArgumentException().isThrownBy(() -> ContextKey.of(name, String.class, "d"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"boundrail.tenant", "boundrail.x.y"})
    @DisplayName("CORE-01 rejects the reserved boundrail. prefix")
    void rejectsTheReservedPrefix(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> ContextKey.of(name, String.class));
        assertThatIllegalArgumentException().isThrownBy(() -> ContextKey.of(name, String.class, "d"));
    }

    static Stream<Arguments> primitiveTypes() {
        return Stream.of(
                Arguments.of(long.class, 1L),
                Arguments.of(int.class, 1),
                Arguments.of(boolean.class, true));
    }

    @ParameterizedTest
    @MethodSource("primitiveTypes")
    @DisplayName("CORE-01 rejects primitive types")
    @SuppressWarnings("unchecked")
    void rejectsPrimitiveTypes(Class<?> type, Object defaultValue) {
        assertThatIllegalArgumentException().isThrownBy(() -> ContextKey.of("x", type));
        assertThatIllegalArgumentException().isThrownBy(() -> ContextKey.of("x", (Class<Object>) type, defaultValue));
    }

    @Test
    @DisplayName("CORE-03 rejects a null name or type with NullPointerException")
    void rejectsANullNameOrType() {
        assertThatNullPointerException().isThrownBy(() -> ContextKey.of(null, String.class));
        assertThatNullPointerException().isThrownBy(() -> ContextKey.of("x", null));
        assertThatNullPointerException().isThrownBy(() -> ContextKey.of(null, String.class, "d"));
        assertThatNullPointerException().isThrownBy(() -> ContextKey.of("x", null, "d"));
    }

    @Test
    @DisplayName("CORE-03 rejects a null default")
    void rejectsANullDefault() {
        assertThatNullPointerException().isThrownBy(() -> ContextKey.of("x", String.class, null));
    }

    static Stream<Named<ThrowingCallable>> nullWithInvalidArgument() {
        return Stream.of(
                Named.<ThrowingCallable>of("of(\"boundrail.x\", String.class, null)", () -> ContextKey.of("boundrail.x", String.class, null)),
                Named.<ThrowingCallable>of("of(\"Bad\", null)", () -> ContextKey.of("Bad", null)),
                Named.<ThrowingCallable>of("of(\"boundrail.x\", null)", () -> ContextKey.of("boundrail.x", null)),
                Named.<ThrowingCallable>of("of(\"x\", long.class, null)", () -> ContextKey.of("x", long.class, null)),
                Named.<ThrowingCallable>of("of(null, long.class)", () -> ContextKey.of(null, long.class)));
    }

    @ParameterizedTest
    @MethodSource("nullWithInvalidArgument")
    @DisplayName("CORE-03 a null argument takes precedence over CORE-01 checks")
    void aNullArgumentTakesPrecedenceOverCore01Checks(ThrowingCallable call) {
        assertThatNullPointerException().isThrownBy(call);
    }

    @Test
    @DisplayName("CORE-01 name, type and defaultValue return what was given")
    void accessorsReturnWhatWasGiven() {
        ContextKey<Long> withDefault = ContextKey.of("acme.retries", Long.class, 3L);
        ContextKey<String> withoutDefault = ContextKey.of("acme.tenant", String.class);

        assertThat(withDefault.name()).isEqualTo("acme.retries");
        assertThat(withDefault.type()).isEqualTo(Long.class);
        assertThat(withDefault.defaultValue()).contains(3L);
        assertThat(withoutDefault.name()).isEqualTo("acme.tenant");
        assertThat(withoutDefault.type()).isEqualTo(String.class);
        assertThat(withoutDefault.defaultValue()).isEmpty();
    }
}
