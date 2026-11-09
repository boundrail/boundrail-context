package io.github.boundrail.context.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.internal.RuntimeVersionMarker;
import io.github.boundrail.context.test.TestInboundTrustVerifier;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
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
    @DisplayName("TEST-04 junit-jupiter-api and assertj-core are api dependencies of boundrail-context-test")
    void junitJupiterApiAndAssertjCoreAreApiDependencies() throws Exception {
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
        assertThat(pomDependencies).as("POM dependencies (artifactId:scope)").contains("junit-jupiter-api:compile", "assertj-core:compile");

        JsonNode module = JsonMapper.builder().build().readTree(Path.of(metadata).toFile());
        List<String> apiModules = new ArrayList<>();
        for (JsonNode variant : module.path("variants")) {
            if (variant.path("name").asString().equals("apiElements")) {
                for (JsonNode dependency : variant.path("dependencies")) {
                    apiModules.add(dependency.path("module").asString());
                }
            }
        }
        assertThat(apiModules).as("modules of the apiElements variant").contains("junit-jupiter-api", "assertj-core");
    }

    @Test
    @DisplayName("REL-04 each published project has a POM under io.github.boundrail at 0.1.0, with sources and Javadoc")
    void eachPublishedProjectHasAPomUnderTheGroupWithSourcesAndJavadoc() throws Exception {
        String poms = System.getProperty("boundrail.it.published-poms");
        assertThat(poms).as("system property boundrail.it.published-poms (set by the integration test task)").isNotNull();

        SoftAssertions softly = new SoftAssertions();
        List<String> artifactIds = new ArrayList<>();
        for (String path : poms.split(File.pathSeparator)) {
            Path pom = Path.of(path);
            Path metadata = pom.resolveSibling("module.json");
            if (!Files.exists(pom) || !Files.exists(metadata)) {
                softly.fail("missing POM or module metadata: %s, %s", pom, metadata);
                continue;
            }

            Element project = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
            String artifactId = childText(project, "artifactId");
            artifactIds.add(artifactId);
            softly.assertThat(childText(project, "groupId")).as("groupId in %s", pom).isEqualTo("io.github.boundrail");
            softly.assertThat(childText(project, "version")).as("version in %s", pom).isEqualTo("0.1.0");

            JsonNode module = JsonMapper.builder().build().readTree(metadata.toFile());
            List<String> variants = new ArrayList<>();
            for (JsonNode variant : module.path("variants")) {
                variants.add(variant.path("name").asString());
            }
            softly.assertThat(variants).as("variants in %s", metadata).contains("sourcesElements", "javadocElements");
        }
        softly.assertThat(artifactIds)
                .as("artifactIds of the published POMs")
                .containsExactlyInAnyOrder(
                        "boundrail-context-core", "boundrail-context-spring", "boundrail-context-kafka", "boundrail-context-test");
        softly.assertAll();
    }

    @Test
    @DisplayName("TEST-01 the boundrail-context-test JAR auto-configures only the TEST-02 violation recorder")
    void testModuleJarAutoConfiguresOnlyTheViolationRecorder() throws Exception {
        Path jar = Path.of(TestInboundTrustVerifier.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertThat(jar.getFileName().toString()).endsWith(".jar");

        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry imports = file.getJarEntry("META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
            assertThat(imports).as("the imports file").isNotNull();
            List<String> candidates;
            try (InputStream in = file.getInputStream(imports)) {
                candidates = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                        .map(String::strip)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .toList();
            }
            assertThat(candidates).containsExactly("io.github.boundrail.context.test.BoundrailTestAutoConfiguration");
            assertThat(file.getJarEntry("META-INF/spring.factories")).isNull();
        }
    }

    /** Returns the text of the direct child element of {@code parent} with the given name, or {@code null} if there is none. */
    private static String childText(Element parent, String name) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && element.getTagName().equals(name)) {
                return element.getTextContent();
            }
        }
        return null;
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
