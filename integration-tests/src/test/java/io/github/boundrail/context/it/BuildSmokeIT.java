package io.github.boundrail.context.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.internal.RuntimeVersionMarker;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuildSmokeIT {

    private static final String MARKER = "io/github/boundrail/context/internal/RuntimeVersionMarker.class";

    @Test
    @DisplayName("BLD-11 versioned marker class loads on JDK 25 only")
    void versionedMarkerClassLoadsOnJdk25Only() {
        int expected = Runtime.version().feature() >= 25 ? 25 : 21;

        assertThat(RuntimeVersionMarker.version()).isEqualTo(expected);
    }

    @Test
    @DisplayName("BLD-04 base classes are compiled for release 21")
    void baseClassesAreCompiledForRelease21() throws Exception {
        assertThat(classFileMajorVersion(MARKER)).isEqualTo(65);
    }

    @Test
    @DisplayName("BLD-04 versioned classes are compiled for release 25")
    void versionedClassesAreCompiledForRelease25() throws Exception {
        assertThat(classFileMajorVersion("META-INF/versions/25/" + MARKER)).isEqualTo(69);
    }

    @Test
    @DisplayName("BLD-06 library classes load from packaged JARs")
    void libraryClassesLoadFromPackagedJars() throws Exception {
        Path location = Path.of(RuntimeVersionMarker.class.getProtectionDomain().getCodeSource().getLocation().toURI());

        assertThat(location.getFileName().toString()).endsWith(".jar");
    }

    @Test
    @DisplayName("BLD-06 runs on the JDK its task declares")
    void runsOnTheJdkItsTaskDeclares() {
        String task = System.getProperty("boundrail.it.task");
        assertThat(task).as("system property boundrail.it.task (set by the integration test task)").isNotNull();
        Matcher name = Pattern.compile("integrationTestJdk(\\d+).*").matcher(task);
        assertThat(name.matches()).as("task name %s names its JDK", task).isTrue();

        assertThat(Runtime.version().feature()).isEqualTo(Integer.parseInt(name.group(1)));
    }

    /** Reads the class file major version of a core JAR entry, bypassing multi-release resolution. */
    private static int classFileMajorVersion(String entry) throws IOException, URISyntaxException {
        Path jar = Path.of(RuntimeVersionMarker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (JarFile file = new JarFile(jar.toFile());
                DataInputStream in = new DataInputStream(file.getInputStream(file.getJarEntry(entry)))) {
            in.readInt(); // magic
            in.readUnsignedShort(); // minor version
            return in.readUnsignedShort();
        }
    }
}
