package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class CodecsTest {

    @Test
    @DisplayName("WIRE-03 each Codecs method returns the same instance on every call")
    void eachCodecsMethodReturnsTheSameInstanceOnEveryCall() {
        assertThat(Codecs.string()).isSameAs(Codecs.string());
        assertThat(Codecs.longValue()).isSameAs(Codecs.longValue());
        assertThat(Codecs.uuid()).isSameAs(Codecs.uuid());
        assertThat(Codecs.locale()).isSameAs(Codecs.locale());
    }

    @Test
    @DisplayName("WIRE-03 encode and decode throw NullPointerException for null")
    void encodeAndDecodeThrowNullPointerExceptionForNull() {
        assertThatNullPointerException().isThrownBy(() -> Codecs.string().encode(null));
        assertThatNullPointerException().isThrownBy(() -> Codecs.string().decode(null));
        assertThatNullPointerException().isThrownBy(() -> Codecs.longValue().encode(null));
        assertThatNullPointerException().isThrownBy(() -> Codecs.longValue().decode(null));
        assertThatNullPointerException().isThrownBy(() -> Codecs.uuid().encode(null));
        assertThatNullPointerException().isThrownBy(() -> Codecs.uuid().decode(null));
        assertThatNullPointerException().isThrownBy(() -> Codecs.locale().encode(null));
        assertThatNullPointerException().isThrownBy(() -> Codecs.locale().decode(null));
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

    @ParameterizedTest
    @ValueSource(strings = {
        "00000000-0000-0000-0000-000000000000",
        "ffffffff-ffff-ffff-ffff-ffffffffffff",
        "123e4567-e89b-12d3-a456-426614174000"})
    @DisplayName("WIRE-03 uuid round-trips valid values and encodes in lower case")
    void uuidRoundTripsValidValuesAndEncodesInLowerCase(String lowerCase) {
        ContextCodec<UUID> codec = Codecs.uuid();
        UUID value = UUID.fromString(lowerCase);

        assertThat(codec.encode(value)).isEqualTo(lowerCase);
        assertThat(codec.decode(codec.encode(value))).isEqualTo(value);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "123e4567-e89b-12d3-a456-426614174000",
        "123E4567-E89B-12D3-A456-426614174000",
        "123e4567-E89B-12d3-A456-426614174000"})
    @DisplayName("WIRE-03 uuid decodes hexadecimal digits in either case")
    void uuidDecodesHexadecimalDigitsInEitherCase(String text) {
        assertThat(Codecs.uuid().decode(text)).isEqualTo(new UUID(0x123e4567e89b12d3L, 0xa456426614174000L));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "1-2-3-4-5",
        "{123e4567-e89b-12d3-a456-426614174000}",
        "urn:uuid:123e4567-e89b-12d3-a456-426614174000",
        "123e4567e89b12d3a456426614174000",
        "123e4567-e89b-12d3-a456-42661417400g"})
    @DisplayName("WIRE-03 uuid rejects invalid input with IllegalArgumentException")
    void uuidRejectsInvalidInputWithIllegalArgumentException(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> Codecs.uuid().decode(text));
    }

    static Stream<Locale> localesWhoseLanguageTagMapsBackToThem() {
        return Stream.of(Locale.ROOT, Locale.of("ja", "JP", "JP"), Locale.of("en", "GB"), Locale.forLanguageTag("zh-Hant-TW"));
    }

    @ParameterizedTest
    @MethodSource("localesWhoseLanguageTagMapsBackToThem")
    @DisplayName("WIRE-03 locale round-trips locales whose language tag maps back to them")
    void localeRoundTripsLocalesWhoseLanguageTagMapsBackToThem(Locale value) {
        ContextCodec<Locale> codec = Codecs.locale();

        assertThat(codec.encode(value)).isEqualTo(value.toLanguageTag());
        assertThat(codec.decode(codec.encode(value))).isEqualTo(value);
    }

    static Stream<Locale> localesWhoseLanguageTagDoesNotMapBackToThem() {
        return Stream.of(Locale.of("no", "NO", "NY"), Locale.of("xyz123"));
    }

    @ParameterizedTest
    @MethodSource("localesWhoseLanguageTagDoesNotMapBackToThem")
    @DisplayName("WIRE-03 locale encodes other locales without error")
    void localeEncodesOtherLocalesWithoutError(Locale value) {
        assertThat(Codecs.locale().encode(value)).isEqualTo(value.toLanguageTag());
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "EN-gb", "zh-Hant-TW", "es-419", "und", "x-foo", "i-klingon", "I-KLINGON"})
    @DisplayName("WIRE-03 locale decodes RFC 5646 language tags ignoring case")
    void localeDecodesRfc5646LanguageTagsIgnoringCase(String text) {
        assertThat(Codecs.locale().decode(text)).isEqualTo(Locale.forLanguageTag(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "en_GB", "en-", "e", "en--GB", " en"})
    @DisplayName("WIRE-03 locale rejects invalid input with IllegalArgumentException")
    void localeRejectsInvalidInputWithIllegalArgumentException(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> Codecs.locale().decode(text));
    }

    static Stream<String> longLanguageTags() {
        return Stream.of("x" + "-ab".repeat(3000), "en-a" + "-bc".repeat(3000), "en" + "-abcde".repeat(1500));
    }

    @ParameterizedTest
    @MethodSource("longLanguageTags")
    @DisplayName("WIRE-03 locale decodes and rejects long tags without stack overflow")
    void localeDecodesAndRejectsLongTagsWithoutStackOverflow(String text) {
        assertThat(Codecs.locale().decode(text)).isEqualTo(Locale.forLanguageTag(text));
        assertThatIllegalArgumentException().isThrownBy(() -> Codecs.locale().decode(text + "-!"));
    }
}
