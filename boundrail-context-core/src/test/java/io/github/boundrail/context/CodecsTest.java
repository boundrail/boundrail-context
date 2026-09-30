package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CodecsTest {

    @Test
    @DisplayName("WIRE-03 each Codecs method returns the same instance on every call")
    void eachCodecsMethodReturnsTheSameInstanceOnEveryCall() {
        assertThat(Codecs.string()).isSameAs(Codecs.string());
        assertThat(Codecs.longValue()).isSameAs(Codecs.longValue());
    }

    @Test
    @DisplayName("WIRE-03 encode and decode throw NullPointerException for null")
    void encodeAndDecodeThrowNullPointerExceptionForNull() {
        assertThatNullPointerException().isThrownBy(() -> Codecs.string().encode(null));
        assertThatNullPointerException().isThrownBy(() -> Codecs.string().decode(null));
        assertThatNullPointerException().isThrownBy(() -> Codecs.longValue().encode(null));
        assertThatNullPointerException().isThrownBy(() -> Codecs.longValue().decode(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "acme", " padded ", "+1", "٣", "line\nbreak", "é中😀"})
    @DisplayName("WIRE-03 string round-trips every string unchanged")
    void stringRoundTripsEveryStringUnchanged(String value) {
        ContextCodec<String> codec = Codecs.string();

        assertThat(codec.encode(value)).isEqualTo(value);
        assertThat(codec.decode(value)).isEqualTo(value);
        assertThat(codec.decode(codec.encode(value))).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE})
    @DisplayName("WIRE-03 longValue round-trips valid values")
    void longValueRoundTripsValidValues(long value) {
        ContextCodec<Long> codec = Codecs.longValue();

        assertThat(codec.encode(value)).isEqualTo(Long.toString(value));
        assertThat(codec.decode(codec.encode(value))).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {"+1", " 1", "007", "-0", "٣", "9223372036854775808", ""})
    @DisplayName("WIRE-03 longValue rejects invalid input with IllegalArgumentException")
    void longValueRejectsInvalidInputWithIllegalArgumentException(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> Codecs.longValue().decode(text));
    }
}
