package io.github.boundrail.context.internal;

/**
 * Java 25 variant of the build marker, packaged under {@code META-INF/versions/25} (BLD-04, BLD-11).
 */
public final class RuntimeVersionMarker {

    private RuntimeVersionMarker() {
    }

    /** {@return 25, the Java release this variant is compiled for} */
    public static int version() {
        return 25;
    }
}
