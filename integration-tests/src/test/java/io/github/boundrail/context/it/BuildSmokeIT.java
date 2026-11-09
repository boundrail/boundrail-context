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
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.MatchResult;
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
    @DisplayName("REL-04 every dependency in each published POM has a version")
    void everyDependencyInEachPublishedPomHasAVersion() throws Exception {
        String poms = System.getProperty("boundrail.it.published-poms");
        assertThat(poms).as("system property boundrail.it.published-poms (set by the integration test task)").isNotNull();

        SoftAssertions softly = new SoftAssertions();
        for (String path : poms.split(File.pathSeparator)) {
            Path pom = Path.of(path);
            Element project = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
            boolean importsBom = false;
            Element management = childElement(project, "dependencyManagement");
            Element managed = management == null ? null : childElement(management, "dependencies");
            if (managed != null) {
                for (Element dependency : childElements(managed, "dependency")) {
                    importsBom |= "import".equals(childText(dependency, "scope")) && "pom".equals(childText(dependency, "type"));
                }
            }
            Element dependencies = childElement(project, "dependencies");
            if (dependencies == null) {
                continue;
            }
            for (Element dependency : childElements(dependencies, "dependency")) {
                String module = childText(dependency, "groupId") + ":" + childText(dependency, "artifactId");
                String version = childText(dependency, "version");
                if ("io.github.boundrail".equals(childText(dependency, "groupId"))) {
                    softly.assertThat(version).as("version of %s in %s", module, pom).isEqualTo("0.1.0");
                } else if (version == null || version.isBlank()) {
                    softly.assertThat(importsBom)
                            .as("%s in %s has no version and the POM imports no BOM", module, pom)
                            .isTrue();
                }
            }
        }
        softly.assertAll();
    }

    @Test
    @DisplayName("REL-04 each published POM has the metadata Central requires")
    void eachPublishedPomHasTheMetadataCentralRequires() throws Exception {
        String poms = System.getProperty("boundrail.it.published-poms");
        assertThat(poms).as("system property boundrail.it.published-poms (set by the integration test task)").isNotNull();
        String repository = "https://github.com/boundrail/boundrail-context";
        Map<String, String> descriptions = Map.of(
                "boundrail-context-core",
                "keys, snapshots, scopes, policy, codecs, destination classification, deadline and identity types, both backends;"
                        + " zero runtime dependencies",
                "boundrail-context-spring",
                "Spring Boot 4 auto-configuration: servlet filters, trust, RestClient/RestTemplate, executors, @Scheduled, MDC, audit,"
                        + " security contributors",
                "boundrail-context-kafka",
                "Spring Kafka producer and consumer adapters",
                "boundrail-context-test",
                "JUnit Jupiter extension, assertions, test trust verifier");

        SoftAssertions softly = new SoftAssertions();
        for (String path : poms.split(File.pathSeparator)) {
            Path pom = Path.of(path);
            Element project = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
            String artifactId = childText(project, "artifactId");
            softly.assertThat(childText(project, "name")).as("name in %s", pom).isEqualTo(artifactId);
            softly.assertThat(childText(project, "description")).as("description in %s", pom).isEqualTo(descriptions.get(artifactId));
            softly.assertThat(childText(project, "url")).as("url in %s", pom).isEqualTo(repository);

            Element licenses = childElement(project, "licenses");
            List<String> licenceEntries = new ArrayList<>();
            if (licenses != null) {
                for (Element license : childElements(licenses, "license")) {
                    licenceEntries.add(childText(license, "name") + " " + childText(license, "url"));
                }
            }
            softly.assertThat(licenceEntries)
                    .as("licences in %s", pom)
                    .containsExactly("Apache-2.0 https://www.apache.org/licenses/LICENSE-2.0");

            Element scm = childElement(project, "scm");
            softly.assertThat(scm).as("scm in %s", pom).isNotNull();
            if (scm != null) {
                softly.assertThat(childText(scm, "url")).as("scm url in %s", pom).isEqualTo(repository);
                softly.assertThat(childText(scm, "connection"))
                        .as("scm connection in %s", pom)
                        .isEqualTo("scm:git:https://github.com/boundrail/boundrail-context.git");
                softly.assertThat(childText(scm, "developerConnection"))
                        .as("scm developerConnection in %s", pom)
                        .isEqualTo("scm:git:ssh://git@github.com/boundrail/boundrail-context.git");
            }
        }
        softly.assertAll();
    }

    @Test
    @DisplayName("REL-04 the developers section of each published POM names jordi1st with https://github.com/jordi1st")
    void developersSectionOfEachPublishedPomNamesTheMaintainer() throws Exception {
        String poms = System.getProperty("boundrail.it.published-poms");
        assertThat(poms).as("system property boundrail.it.published-poms (set by the integration test task)").isNotNull();

        SoftAssertions softly = new SoftAssertions();
        for (String path : poms.split(File.pathSeparator)) {
            Path pom = Path.of(path);
            Element project = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
            Element developers = childElement(project, "developers");
            List<String> developerEntries = new ArrayList<>();
            if (developers != null) {
                for (Element developer : childElements(developers, "developer")) {
                    developerEntries.add(childText(developer, "id") + " " + childText(developer, "name") + " " + childText(developer, "url"));
                }
            }
            softly.assertThat(developerEntries)
                    .as("developers (id name url) in %s", pom)
                    .containsExactly("jordi1st jordi1st https://github.com/jordi1st");
        }
        softly.assertAll();
    }

    @Test
    @DisplayName("REL-04 no published POM or module metadata contains an email address")
    void noPublishedPomOrModuleMetadataContainsAnEmailAddress() throws Exception {
        String poms = System.getProperty("boundrail.it.published-poms");
        assertThat(poms).as("system property boundrail.it.published-poms (set by the integration test task)").isNotNull();
        // An address, but not the user of a URL such as the SCM's ssh://git@github.com/...
        Pattern address = Pattern.compile("(?<![\\w.%+-])(?<!://)[\\w.%+-]+@[\\w-]+(\\.[\\w-]+)*\\.[A-Za-z]{2,}");

        SoftAssertions softly = new SoftAssertions();
        for (String path : poms.split(File.pathSeparator)) {
            Path pom = Path.of(path);
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
            softly.assertThat(document.getElementsByTagName("email").getLength()).as("email elements in %s", pom).isZero();
            for (Path file : List.of(pom, pom.resolveSibling("module.json"))) {
                softly.assertThat(address.matcher(Files.readString(file)).results().map(MatchResult::group).toList())
                        .as("email addresses in %s", file)
                        .isEmpty();
            }
        }
        softly.assertAll();
    }

    @Test
    @DisplayName("REL-04 core's sources JAR holds the java25 sources under META-INF/versions/25")
    void coreSourcesJarHoldsTheJava25SourcesUnderMetaInfVersions25() throws Exception {
        String sourcesJar = System.getProperty("boundrail.it.core-sources-jar");
        assertThat(sourcesJar).as("system property boundrail.it.core-sources-jar (set by the integration test task)").isNotNull();
        String root = System.getProperty("boundrail.it.repository-root");
        assertThat(root).as("system property boundrail.it.repository-root (set by the integration test task)").isNotNull();
        Path sources = Path.of(root, "boundrail-context-core/src/main");
        String internal = "io/github/boundrail/context/internal/";

        try (JarFile file = new JarFile(Path.of(sourcesJar).toFile())) {
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(entryText(file, internal + "RuntimeVersionMarker.java"))
                    .as("base RuntimeVersionMarker.java")
                    .isEqualTo(Files.readString(sources.resolve("java/" + internal + "RuntimeVersionMarker.java")));
            softly.assertThat(entryText(file, "META-INF/versions/25/" + internal + "RuntimeVersionMarker.java"))
                    .as("versioned RuntimeVersionMarker.java")
                    .isEqualTo(Files.readString(sources.resolve("java25/" + internal + "RuntimeVersionMarker.java")));
            softly.assertThat(entryText(file, "META-INF/versions/25/" + internal + "ScopedValueSlot.java"))
                    .as("versioned ScopedValueSlot.java")
                    .isEqualTo(Files.readString(sources.resolve("java25/" + internal + "ScopedValueSlot.java")));
            softly.assertAll();
        }
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

    /** Returns the first direct child element of {@code parent} with the given name, or {@code null} if there is none. */
    private static Element childElement(Element parent, String name) {
        List<Element> children = childElements(parent, name);
        return children.isEmpty() ? null : children.getFirst();
    }

    /** Returns the direct child elements of {@code parent} with the given name. */
    private static List<Element> childElements(Element parent, String name) {
        List<Element> children = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && element.getTagName().equals(name)) {
                children.add(element);
            }
        }
        return children;
    }

    /** Returns the UTF-8 text of a JAR entry, or {@code null} if the JAR has no such entry. */
    private static String entryText(JarFile file, String name) throws IOException {
        JarEntry entry = file.getJarEntry(name);
        if (entry == null) {
            return null;
        }
        try (InputStream in = file.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
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
