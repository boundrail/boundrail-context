package io.github.boundrail.context.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.internal.RuntimeVersionMarker;
import io.github.boundrail.context.test.TestInboundTrustVerifier;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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

    @Test
    @DisplayName("TEST-01 boundrail-context-test publishes boundrail-context-spring as an api dependency and no spring-web or servlet API")
    void testModulePublishesSpringAsApiDependency() throws Exception {
        String pom = System.getProperty("boundrail.it.test-module.pom");
        assertThat(pom).as("system property boundrail.it.test-module.pom (set by the integration test task)").isNotNull();
        String metadata = System.getProperty("boundrail.it.test-module.metadata");
        assertThat(metadata).as("system property boundrail.it.test-module.metadata (set by the integration test task)").isNotNull();

        Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Path.of(pom).toFile());
        List<String> pomDependencies = new ArrayList<>();
        NodeList dependencies = document.getElementsByTagName("dependency");
        for (int i = 0; i < dependencies.getLength(); i++) {
            Element dependency = (Element) dependencies.item(i);
            NodeList scope = dependency.getElementsByTagName("scope");
            pomDependencies.add(dependency.getElementsByTagName("artifactId").item(0).getTextContent() + ":"
                    + (scope.getLength() == 0 ? "" : scope.item(0).getTextContent()));
        }
        assertThat(pomDependencies)
                .as("POM dependencies (artifactId:scope)")
                .contains("boundrail-context-spring:compile")
                .noneMatch(d -> d.startsWith("spring-web:") || d.startsWith("jakarta.servlet-api:"));

        JsonNode module = JsonMapper.builder().build().readTree(Path.of(metadata).toFile());
        List<String> apiModules = new ArrayList<>();
        List<String> allModules = new ArrayList<>();
        for (JsonNode variant : module.path("variants")) {
            for (JsonNode dependency : variant.path("dependencies")) {
                String name = dependency.path("module").asString();
                allModules.add(name);
                if (variant.path("name").asString().equals("apiElements")) {
                    apiModules.add(name);
                }
            }
        }
        assertThat(apiModules).as("modules of the apiElements variant").contains("boundrail-context-spring");
        assertThat(allModules).as("modules of every variant").doesNotContain("spring-web", "jakarta.servlet-api");
    }

    @Test
    @DisplayName("TEST-01 the boundrail-context-test JAR ships no AutoConfiguration.imports and no spring.factories")
    void testModuleJarShipsNoAutoConfiguration() throws Exception {
        Path jar = Path.of(TestInboundTrustVerifier.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertThat(jar.getFileName().toString()).endsWith(".jar");

        try (JarFile file = new JarFile(jar.toFile())) {
            assertThat(file.getJarEntry("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")).isNull();
            assertThat(file.getJarEntry("META-INF/spring.factories")).isNull();
        }
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
