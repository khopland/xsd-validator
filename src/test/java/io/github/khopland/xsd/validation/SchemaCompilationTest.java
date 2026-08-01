package io.github.khopland.xsd.validation;

import static io.github.khopland.xsd.validation.TestSources.CHOICE_SCHEMA;
import static io.github.khopland.xsd.validation.TestSources.compile;
import static io.github.khopland.xsd.validation.TestSources.xml;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import org.apache.xerces.dom.DOMInputImpl;
import org.apache.xerces.jaxp.SAXParserFactoryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.XMLFilterImpl;

class SchemaCompilationTest {
    @Test
    void enforcesRootSchemaByteLimitsForByteAndCharacterSources() throws Exception {
        String schema = """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:annotation><xs:documentation>blå</xs:documentation></xs:annotation>
                </xs:schema>
                """;
        byte[] bytes = schema.getBytes(StandardCharsets.UTF_8);
        SchemaCompilationLimits exactLimits =
                new SchemaCompilationLimits(bytes.length, 1, 1024, 1024);
        SchemaCompilationLimits smallerLimits =
                new SchemaCompilationLimits(bytes.length - 1, 1, 1024, 1024);

        assertThat(BetterXsdValidator.compile(
                        new StreamSource(new ByteArrayInputStream(bytes)),
                        exactLimits))
                .isNotNull();
        assertThat(BetterXsdValidator.compile(
                        new StreamSource(new StringReader(schema)),
                        exactLimits))
                .isNotNull();
        assertThatExceptionOfType(SchemaCompilationException.class)
                .isThrownBy(() -> BetterXsdValidator.compile(
                        new StreamSource(new ByteArrayInputStream(bytes)),
                        smallerLimits))
                .withMessageContaining("Root schema exceeds");
        assertThatExceptionOfType(SchemaCompilationException.class)
                .isThrownBy(() -> BetterXsdValidator.compile(
                        new StreamSource(new StringReader(schema)),
                        smallerLimits))
                .withMessageContaining("Root schema exceeds");
    }

    @Test
    void enforcesThePerDependencyByteLimit(@TempDir Path directory) throws Exception {
        String dependency = emptySchema("dependency-content");
        Path root = schemaSet(directory, dependency, null);
        int dependencyBytes = dependency.getBytes(StandardCharsets.UTF_8).length;
        SchemaCompilationLimits exactLimits =
                new SchemaCompilationLimits(4096, 2, dependencyBytes, dependencyBytes);
        SchemaCompilationLimits smallerLimits =
                new SchemaCompilationLimits(4096, 2, dependencyBytes - 1, 4096);

        assertThat(BetterXsdValidator.compile(
                        new StreamSource(root.toFile()),
                        exactLimits))
                .isNotNull();
        assertThatExceptionOfType(SchemaCompilationException.class)
                .isThrownBy(() -> BetterXsdValidator.compile(
                        new StreamSource(root.toFile()),
                        smallerLimits))
                .withMessage("Schema dependency exceeds its configured limit of "
                        + (dependencyBytes - 1) + " bytes.");
    }

    @Test
    void rejectsTooManyDistinctSchemaDependencies(@TempDir Path directory) throws Exception {
        String dependency = emptySchema("dependency-content");
        Path root = schemaSet(directory, dependency, dependency);
        SchemaCompilationLimits limits =
                new SchemaCompilationLimits(4096, 1, 4096, 8192);

        assertThatExceptionOfType(SchemaCompilationException.class)
                .isThrownBy(() -> BetterXsdValidator.compile(
                        new StreamSource(root.toFile()),
                        limits))
                .withMessage("Schema dependency count exceeds its configured limit of 1.");
    }

    @Test
    void rejectsSchemaDependenciesAboveTheTotalByteLimit(@TempDir Path directory)
            throws Exception {
        String firstDependency = emptySchema("first");
        String secondDependency = emptySchema("second");
        Path root = schemaSet(directory, firstDependency, secondDependency);
        long totalBytes = firstDependency.getBytes(StandardCharsets.UTF_8).length
                + secondDependency.getBytes(StandardCharsets.UTF_8).length;
        SchemaCompilationLimits limits =
                new SchemaCompilationLimits(4096, 2, 4096, totalBytes - 1);

        assertThatExceptionOfType(SchemaCompilationException.class)
                .isThrownBy(() -> BetterXsdValidator.compile(
                        new StreamSource(root.toFile()),
                        limits))
                .withMessage("Total schema dependency content exceeds its configured limit of "
                        + (totalBytes - 1) + " bytes.");
    }

    @Test
    void rejectsNonPositiveSchemaCompilationLimits() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new SchemaCompilationLimits(0, 1, 1, 1))
                .withMessageContaining("maxRootSchemaBytes");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new SchemaCompilationLimits(1, 0, 1, 1))
                .withMessageContaining("maxDependencyCount");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new SchemaCompilationLimits(1, 1, 0, 1))
                .withMessageContaining("maxDependencyBytes");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new SchemaCompilationLimits(1, 1, 1, 0))
                .withMessageContaining("maxTotalDependencyBytes");
    }

    @Test
    void requiresABaseUriForRelativeSchemaDependencies() {
        assertThatExceptionOfType(SchemaCompilationException.class)
                .isThrownBy(() -> compile("""
                        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                          <xs:include schemaLocation="types.xsd"/>
                        </xs:schema>
                        """))
                .withMessageContaining("system ID");
    }

    @Test
    void allowsImportsWithoutASchemaLocation() throws SchemaCompilationException {
        assertThat(compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:import namespace="urn:dependency"/>
                </xs:schema>
                """)).isNotNull();
    }

    @Test
    void rejectsNonFileSchemaSystemIds() {
        StreamSource source = new StreamSource();
        source.setSystemId("https://example.com/schema.xsd");

        assertThatExceptionOfType(SchemaCompilationException.class)
                .isThrownBy(() -> BetterXsdValidator.compile(source))
                .withMessageContaining("local file");
    }

    @Test
    void wrapsMalformedSchemaLocations() {
        assertThatExceptionOfType(SchemaCompilationException.class)
                .isThrownBy(() -> compile("""
                        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                          <xs:include schemaLocation="invalid location.xsd"/>
                        </xs:schema>
                        """))
                .withMessageContaining("system ID");
    }

    @Test
    void closesSchemaSourceStreamsAndReaders() throws Exception {
        AtomicBoolean streamClosed = new AtomicBoolean();
        var stream = new ByteArrayInputStream(CHOICE_SCHEMA.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                streamClosed.set(true);
                super.close();
            }
        };
        AtomicBoolean readerClosed = new AtomicBoolean();
        var reader = new StringReader(CHOICE_SCHEMA) {
            @Override
            public void close() {
                readerClosed.set(true);
                super.close();
            }
        };

        BetterXsdValidator.compile(new StreamSource(stream));
        BetterXsdValidator.compile(new StreamSource(reader));

        assertThat(streamClosed).isTrue();
        assertThat(readerClosed).isTrue();
    }

    @Test
    void compilesACharacterStreamRegardlessOfItsEncodingDeclaration()
            throws Exception {
        BetterXsdValidator validator = BetterXsdValidator.compile(
                new StreamSource(new StringReader("""
                        <?xml version="1.0" encoding="UTF-16"?>
                        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                          <xs:element name="value" type="xs:string"/>
                        </xs:schema>
                        """)));

        assertThat(validator.validate(xml("<value>ok</value>")).valid()).isTrue();
    }

    @Test
    void resolvesFileIncludesWhenTheSourceHasABaseUri(@TempDir Path directory)
            throws Exception {
        Files.writeString(directory.resolve("types.xsd"), """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:simpleType name="Identifier">
                    <xs:restriction base="xs:string"/>
                  </xs:simpleType>
                </xs:schema>
                """);
        Path root = directory.resolve("root.xsd");
        Files.writeString(root, """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:include schemaLocation="types.xsd"/>
                  <xs:element name="id" type="Identifier"/>
                </xs:schema>
                """);

        BetterXsdValidator validator =
                BetterXsdValidator.compile(new StreamSource(root.toFile()));

        ValidationReport firstReport = validator.validate(xml("<id>abc</id>"));
        assertThat(firstReport.valid()).isTrue();
        String firstFingerprint = firstReport.schema().fingerprint();

        Files.writeString(directory.resolve("types.xsd"), """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:simpleType name="Identifier">
                    <xs:restriction base="xs:normalizedString"/>
                  </xs:simpleType>
                </xs:schema>
                """);
        String changedFingerprint = BetterXsdValidator
                .compile(new StreamSource(root.toFile()))
                .validate(xml("<id>abc</id>"))
                .schema()
                .fingerprint();

        assertThat(changedFingerprint).isNotEqualTo(firstFingerprint);
    }

    @Test
    void resolvesNonFileSchemaDependenciesWithAnExplicitResolver() throws Exception {
        String dependency = """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:simpleType name="Identifier">
                    <xs:restriction base="xs:string"/>
                  </xs:simpleType>
                </xs:schema>
                """;
        var reports = new java.util.ArrayList<ValidationReport>();

        for (String location : java.util.List.of(
                "memory:/types.xsd",
                "classpath:/types.xsd",
                "jar:file:/application.jar!/types.xsd")) {
            StreamSource root = new StreamSource(new StringReader("""
                    <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                      <xs:include schemaLocation="%s"/>
                      <xs:element name="id" type="Identifier"/>
                    </xs:schema>
                    """.formatted(location)));
            BetterXsdValidator validator = BetterXsdValidator.compile(
                    root,
                    (type, namespaceUri, publicId, systemId, baseUri) -> {
                        DOMInputImpl input = new DOMInputImpl();
                        input.setSystemId(location);
                        input.setStringData(dependency);
                        return input;
                    });
            reports.add(validator.validate(xml("<id>abc</id>")));
        }

        assertThat(reports).allMatch(ValidationReport::valid);
    }

    @Test
    void closesStreamsReturnedByAnExplicitSchemaResolver() throws Exception {
        String root = """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:include schemaLocation="memory:/types.xsd"/>
                  <xs:element name="id" type="Identifier"/>
                </xs:schema>
                """;
        String dependency = """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:simpleType name="Identifier">
                    <xs:restriction base="xs:string"/>
                  </xs:simpleType>
                </xs:schema>
                """;
        AtomicBoolean byteStreamClosed = new AtomicBoolean();
        AtomicBoolean characterStreamClosed = new AtomicBoolean();

        BetterXsdValidator.compile(
                new StreamSource(new StringReader(root)),
                (type, namespaceUri, publicId, systemId, baseUri) -> {
                    var input = new DOMInputImpl();
                    input.setSystemId(systemId);
                    input.setByteStream(new ByteArrayInputStream(
                            dependency.getBytes(StandardCharsets.UTF_8)) {
                        @Override
                        public void close() throws IOException {
                            byteStreamClosed.set(true);
                            super.close();
                        }
                    });
                    return input;
                });
        BetterXsdValidator.compile(
                new StreamSource(new StringReader(root)),
                (type, namespaceUri, publicId, systemId, baseUri) -> {
                    var input = new DOMInputImpl();
                    input.setSystemId(systemId);
                    input.setCharacterStream(new StringReader(dependency) {
                        @Override
                        public void close() {
                            characterStreamClosed.set(true);
                            super.close();
                        }
                    });
                    return input;
                });

        assertThat(byteStreamClosed).isTrue();
        assertThat(characterStreamClosed).isTrue();
    }

    @Test
    void rejectsNetworkSchemaDependencies() {
        assertThatExceptionOfType(SchemaCompilationException.class)
                .isThrownBy(() -> compile("""
                        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                                   xmlns:remote="urn:remote">
                          <xs:import namespace="urn:remote"
                                     schemaLocation="https://example.com/types.xsd"/>
                          <xs:element name="id" type="remote:Identifier"/>
                        </xs:schema>
                        """))
                .withMessage("The XSD 1.0 schema could not be compiled.");
    }

    @Test
    void rejectsDoctypesInIncludedSchemas(@TempDir Path directory) throws Exception {
        Path secret = directory.resolve("secret.txt");
        Files.writeString(secret, "private-schema-value");
        Files.writeString(directory.resolve("dependency.xsd"), """
                <!DOCTYPE xs:schema [
                  <!ENTITY private SYSTEM "%s">
                ]>
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:annotation>
                    <xs:documentation>&private;</xs:documentation>
                  </xs:annotation>
                </xs:schema>
                """.formatted(secret.toUri()));
        Path root = directory.resolve("root.xsd");
        Files.writeString(root, """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:include schemaLocation="dependency.xsd"/>
                  <xs:element name="value" type="xs:string"/>
                </xs:schema>
                """);

        assertThatExceptionOfType(SchemaCompilationException.class)
                .isThrownBy(() -> BetterXsdValidator.compile(new StreamSource(root.toFile())))
                .withMessage("The XSD 1.0 schema could not be compiled.");
    }

    @Test
    void distinguishesUnsupportedSourcesFromMalformedXml() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="value" type="xs:string"/>
                </xs:schema>
                """);
        var document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new StringReader("<value>ok</value>")));

        ValidationReport report = validator.validate(new DOMSource(document));

        assertThat(report.complete()).isFalse();
        assertThat(report.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("XML_PROCESSING_ERROR");
            assertThat(issue.schemaCodes()).containsExactly("xml-processing-stopped");
        });
    }

    @Test
    void usesTheXmlReaderSuppliedByASaxSource() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="value" type="xs:string"/>
                </xs:schema>
                """);
        SAXParserFactoryImpl factory = new SAXParserFactoryImpl();
        factory.setNamespaceAware(true);
        AtomicBoolean readerUsed = new AtomicBoolean();
        XMLFilterImpl reader = new XMLFilterImpl(factory.newSAXParser().getXMLReader()) {
            @Override
            public void parse(InputSource input) throws IOException, org.xml.sax.SAXException {
                readerUsed.set(true);
                super.parse(input);
            }
        };

        ValidationReport report = validator.validate(new SAXSource(
                reader,
                new InputSource(new StringReader("<value>ok</value>"))));

        assertThat(readerUsed).isTrue();
        assertThat(report.valid()).isTrue();
        assertThat(report.complete()).isTrue();
    }

    private static String emptySchema(String documentation) {
        return """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:annotation><xs:documentation>%s</xs:documentation></xs:annotation>
                </xs:schema>
                """.formatted(documentation);
    }

    private static Path schemaSet(
            Path directory,
            String firstDependency,
            @org.jspecify.annotations.Nullable String secondDependency)
            throws IOException {
        Files.writeString(directory.resolve("first.xsd"), firstDependency);
        String secondInclude = "";
        if (secondDependency != null) {
            Files.writeString(directory.resolve("second.xsd"), secondDependency);
            secondInclude = "<xs:include schemaLocation=\"second.xsd\"/>";
        }
        Path root = directory.resolve("root.xsd");
        Files.writeString(root, """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:include schemaLocation="first.xsd"/>
                  %s
                </xs:schema>
                """.formatted(secondInclude));
        return root;
    }

}
