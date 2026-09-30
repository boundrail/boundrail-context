package io.github.boundrail.context;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** The library's codecs (WIRE-03). Each method returns the same instance on every call. */
public final class Codecs {

    private static final Pattern LONG = Pattern.compile("0|-?[1-9][0-9]*");

    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    // The Language-Tag production of RFC 5646 section 2.1, one constant per ABNF rule, with ASCII-only character classes.
    private static final String ALPHA = "[A-Za-z]";
    private static final String DIGIT = "[0-9]";
    private static final String ALPHANUM = "[A-Za-z0-9]";
    // extlang = 3ALPHA *2("-" 3ALPHA)
    private static final String EXTLANG = ALPHA + "{3}(?:-" + ALPHA + "{3}){0,2}";
    // language = 2*3ALPHA ["-" extlang] / 4ALPHA / 5*8ALPHA
    private static final String LANGUAGE =
            "(?:" + ALPHA + "{2,3}(?:-" + EXTLANG + ")?|" + ALPHA + "{4}|" + ALPHA + "{5,8})";
    // script = 4ALPHA
    private static final String SCRIPT = ALPHA + "{4}";
    // region = 2ALPHA / 3DIGIT
    private static final String REGION = "(?:" + ALPHA + "{2}|" + DIGIT + "{3})";
    // variant = 5*8alphanum / (DIGIT 3alphanum)
    private static final String VARIANT = "(?:" + ALPHANUM + "{5,8}|" + DIGIT + ALPHANUM + "{3})";
    // singleton = DIGIT / %x41-57 / %x59-5A / %x61-77 / %x79-7A (any alphanum except "x" and "X")
    private static final String SINGLETON = "[0-9A-WYZa-wyz]";
    // extension = singleton 1*("-" (2*8alphanum))
    private static final String EXTENSION = SINGLETON + "(?:-" + ALPHANUM + "{2,8})++";
    // privateuse = "x" 1*("-" (1*8alphanum))
    private static final String PRIVATEUSE = "[xX](?:-" + ALPHANUM + "{1,8})++";
    // langtag = language ["-" script] ["-" region] *("-" variant) *("-" extension) ["-" privateuse]
    private static final String LANGTAG = LANGUAGE + "(?:-" + SCRIPT + ")?(?:-" + REGION + ")?(?:-" + VARIANT + ")*+(?:-"
            + EXTENSION + ")*+(?:-" + PRIVATEUSE + ")?";
    // irregular = "en-GB-oed" / "i-ami" / "i-bnn" / "i-default" / "i-enochian" / "i-hak" / "i-klingon" / "i-lux" / "i-mingo"
    //           / "i-navajo" / "i-pwn" / "i-tao" / "i-tay" / "i-tsu" / "sgn-BE-FR" / "sgn-BE-NL" / "sgn-CH-DE"
    private static final String IRREGULAR = "en-GB-oed|i-ami|i-bnn|i-default|i-enochian|i-hak|i-klingon|i-lux|i-mingo"
            + "|i-navajo|i-pwn|i-tao|i-tay|i-tsu|sgn-BE-FR|sgn-BE-NL|sgn-CH-DE";
    // regular = "art-lojban" / "cel-gaulish" / "no-bok" / "no-nyn" / "zh-guoyu" / "zh-hakka" / "zh-min" / "zh-min-nan" / "zh-xiang"
    private static final String REGULAR = "art-lojban|cel-gaulish|no-bok|no-nyn|zh-guoyu|zh-hakka|zh-min|zh-min-nan|zh-xiang";
    // grandfathered = irregular / regular
    private static final String GRANDFATHERED = IRREGULAR + "|" + REGULAR;
    // Language-Tag = langtag / privateuse / grandfathered. CASE_INSENSITIVE without UNICODE_CASE folds ASCII letters only.
    private static final Pattern LANGUAGE_TAG =
            Pattern.compile("(?:" + LANGTAG + ")|(?:" + PRIVATEUSE + ")|(?:" + GRANDFATHERED + ")", Pattern.CASE_INSENSITIVE);

    private static final ContextCodec<String> STRING = new ContextCodec<>() {
        @Override
        public String encode(String value) {
            return Objects.requireNonNull(value, "value");
        }

        @Override
        public String decode(String text) {
            return Objects.requireNonNull(text, "text");
        }
    };

    private static final ContextCodec<Long> LONG_VALUE = new ContextCodec<>() {
        @Override
        public String encode(Long value) {
            return Long.toString(Objects.requireNonNull(value, "value"));
        }

        @Override
        public Long decode(String text) {
            Objects.requireNonNull(text, "text");
            if (!LONG.matcher(text).matches()) {
                throw new IllegalArgumentException("Invalid long: \"" + text + "\"");
            }
            return Long.parseLong(text);
        }
    };

    private static final ContextCodec<UUID> UUID_VALUE = new ContextCodec<>() {
        @Override
        public String encode(UUID value) {
            return Objects.requireNonNull(value, "value").toString();
        }

        @Override
        public UUID decode(String text) {
            Objects.requireNonNull(text, "text");
            if (!UUID_TEXT.matcher(text).matches()) {
                throw new IllegalArgumentException("Invalid UUID: \"" + text + "\"");
            }
            return UUID.fromString(text);
        }
    };

    private static final ContextCodec<Locale> LOCALE = new ContextCodec<>() {
        @Override
        public String encode(Locale value) {
            return Objects.requireNonNull(value, "value").toLanguageTag();
        }

        @Override
        public Locale decode(String text) {
            Objects.requireNonNull(text, "text");
            // Locale.forLanguageTag is lenient and never throws, so the strict check comes first.
            if (!LANGUAGE_TAG.matcher(text).matches()) {
                throw new IllegalArgumentException("Invalid language tag: \"" + text + "\"");
            }
            return Locale.forLanguageTag(text);
        }
    };

    private Codecs() {
    }

    /** {@return the codec that encodes and decodes a string unchanged} */
    public static ContextCodec<String> string() {
        return STRING;
    }

    /**
     * Returns the codec for {@code long} values. {@code encode} is {@link Long#toString(long)}; {@code decode} accepts exactly the strings
     * matching {@code 0|-?[1-9][0-9]*} in ASCII digits that are in the range of {@code long}.
     *
     * @return the long codec
     */
    public static ContextCodec<Long> longValue() {
        return LONG_VALUE;
    }

    /**
     * Returns the codec for {@link UUID} values. {@code encode} is {@link UUID#toString()}, in lower case; {@code decode} accepts exactly
     * 36-character strings of hexadecimal digits, in either case, grouped 8-4-4-4-12 by hyphens.
     *
     * @return the UUID codec
     */
    public static ContextCodec<UUID> uuid() {
        return UUID_VALUE;
    }

    /**
     * Returns the codec for {@link Locale} values. {@code encode} is {@link Locale#toLanguageTag()}; {@code decode} accepts exactly the
     * strings matching the {@code Language-Tag} production of RFC 5646 section 2.1, ignoring case, and returns
     * {@link Locale#forLanguageTag(String)}. Only locales {@code l} for which {@code Locale.forLanguageTag(l.toLanguageTag())} equals
     * {@code l} round-trip; other locales are encoded without error.
     *
     * @return the locale codec
     */
    public static ContextCodec<Locale> locale() {
        return LOCALE;
    }
}
