package io.github.boundrail.context;

import java.util.Objects;
import java.util.regex.Pattern;

/** The library's codecs (WIRE-03). Each method returns the same instance on every call. */
public final class Codecs {

    private static final Pattern LONG = Pattern.compile("0|-?[1-9][0-9]*");

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
}
