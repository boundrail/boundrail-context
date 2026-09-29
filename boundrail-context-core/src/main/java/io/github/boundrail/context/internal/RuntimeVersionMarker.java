package io.github.boundrail.context.internal;

/**
 * Build marker with a base variant and a {@code META-INF/versions/25} variant, used only by the build smoke test (BLD-11) to prove that the
 * multi-release JAR loads the right variant (BLD-04, BLD-11).
 */
public final class RuntimeVersionMarker {

    private RuntimeVersionMarker() {
    }

    /** {@return 21, the Java release this base variant is compiled for} */
    public static int version() {
        return 21;
    }
}
