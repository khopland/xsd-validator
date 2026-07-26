package io.github.khopland.xsd.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.StringReader;
import java.lang.reflect.Modifier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.xml.transform.stream.StreamSource;
import org.junit.jupiter.api.Test;

class ValidationRuntimeTest {
    private static final String CHOICE_SCHEMA = """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                       targetNamespace="urn:contact"
                       xmlns="urn:contact"
                       elementFormDefault="qualified">
              <xs:element name="contact">
                <xs:complexType>
                  <xs:choice minOccurs="0">
                    <xs:sequence>
                      <xs:element name="postalAddress" type="xs:string"/>
                      <xs:element name="postalCode" type="xs:string"/>
                    </xs:sequence>
                    <xs:element name="sms" type="xs:string"/>
                  </xs:choice>
                </xs:complexType>
              </xs:element>
            </xs:schema>
            """;
    @Test
    void exposesSkippedAndLaxWildcardContentInCoverage() throws Exception {
        BetterXsdValidator skipValidator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="root">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:any processContents="skip"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);
        BetterXsdValidator laxValidator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="root">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:any processContents="lax"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        ValidationReport skipped =
                skipValidator.validate(xml("<root><unknown>private</unknown></root>"));
        ValidationReport lax =
                laxValidator.validate(xml("<root><unknown>private</unknown></root>"));

        assertThat(skipped.valid()).isTrue();
        assertThat(skipped.coverage().skippedOrLaxContent()).isTrue();
        assertThat(lax.valid()).isTrue();
        assertThat(lax.coverage().skippedOrLaxContent()).isTrue();
        assertThat(skipped.toString()).doesNotContain("private");
        assertThat(lax.toString()).doesNotContain("private");
    }

    @Test
    void keepsXercesImplementationTypesOutOfThePublicApi() {
        assertThat(java.util.List.of(
                        XercesSchemaCompiler.class,
                        XercesSchemaCompiler.CompiledSchema.class,
                        ValidationObservation.class))
                .allSatisfy(type ->
                        assertThat(Modifier.isPublic(type.getModifiers())).isFalse());
    }

    @Test
    void doesNotMistakeStrictlyValidatedContentForWildcardCoverage() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="child" type="xs:string"/>
                  <xs:element name="root">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:any processContents="strict"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        ValidationReport report =
                validator.validate(xml("<root><child>ok</child></root>"));
        ValidationReport invalid =
                validator.validate(xml("<root><unknown/></root>"));

        assertThat(report.valid()).isTrue();
        assertThat(report.coverage().skippedOrLaxContent()).isFalse();
        assertThat(invalid.valid()).isFalse();
        assertThat(invalid.coverage().skippedOrLaxContent()).isFalse();
    }

    @Test
    void returnsEveryRecoverableErrorInDocumentOrder() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="values">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="first" type="xs:int"/>
                        <xs:element name="second" type="xs:int"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        ValidationReport report = validator.validate(xml("""
                <values>
                  <first>not-one</first>
                  <second>not-two</second>
                </values>
                """));

        assertThat(report.rawEventCount()).isEqualTo(4);
        assertThat(report.issues())
                .extracting(ValidationIssue::path)
                .containsExactly("/values[1]/first[1]", "/values[1]/second[1]");
        assertThat(report.complete()).isTrue();
    }

    @Test
    void capsReturnedIssuesWithoutLosingTheRawEventCount() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="values">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="item" type="xs:int"
                                    minOccurs="0" maxOccurs="unbounded"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);
        String items = "<item>private-value</item>".repeat(501);

        ValidationReport report =
                validator.validate(xml("<values>" + items + "</values>"));

        assertThat(report.rawEventCount()).isEqualTo(1_002);
        assertThat(report.issues()).hasSize(100);
        assertThat(report.valid()).isFalse();
        assertThat(report.coverage().issuesTruncated()).isTrue();
        assertThat(report.toString()).doesNotContain("private-value");
    }

    @Test
    void validatesConcurrentlyWithIsolatedSessions() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="number" type="xs:int"/>
                </xs:schema>
                """);
        var executor = Executors.newFixedThreadPool(4);
        try {
            var tasks = java.util.stream.IntStream.range(0, 40)
                    .mapToObj(index -> (java.util.concurrent.Callable<ValidationReport>) () ->
                            validator.validate(xml(index % 2 == 0
                                    ? "<number>42</number>"
                                    : "<number>private-" + index + "</number>")))
                    .toList();

            var reports = executor.invokeAll(tasks).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        } catch (Exception exception) {
                            throw new AssertionError(exception);
                        }
                    })
                    .toList();

            assertThat(reports).hasSize(40);
            assertThat(reports).filteredOn(ValidationReport::valid).hasSize(20);
            assertThat(reports).filteredOn(report -> !report.valid()).allSatisfy(report -> {
                assertThat(report.issues()).hasSize(1);
                assertThat(report.issues().get(0).code()).isEqualTo("INVALID_VALUE");
                assertThat(report.toString()).doesNotContain("private-");
            });
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void validatesLargeAndDeepDocuments() throws Exception {
        BetterXsdValidator largeValidator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="items">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="item" type="xs:int"
                                    minOccurs="0" maxOccurs="unbounded"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);
        BetterXsdValidator deepValidator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="node">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element ref="node" minOccurs="0"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);
        String largeXml = "<items>" + "<item>1</item>".repeat(10_000) + "</items>";
        String deepXml = "<node>".repeat(200) + "</node>".repeat(200);

        assertThat(largeValidator.validate(xml(largeXml)).valid()).isTrue();
        assertThat(deepValidator.validate(xml(deepXml)).valid()).isTrue();
    }

    @Test
    void stopsBeforePathTrackingLimitsCanGrowWithoutBound() throws Exception {
        BetterXsdValidator deepValidator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="node">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element ref="node" minOccurs="0"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);
        BetterXsdValidator namesValidator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="root">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:any processContents="skip" maxOccurs="unbounded"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);
        String tooDeep = "<node>".repeat(257) + "</node>".repeat(257);
        String distinctChildren = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> "<name" + index + "/>")
                .reduce("", String::concat);

        ValidationReport depthReport = deepValidator.validate(xml(tooDeep));
        ValidationReport namesReport =
                namesValidator.validate(xml("<root>" + distinctChildren + "</root>"));

        assertThat(depthReport.complete()).isFalse();
        assertThat(depthReport.issues()).singleElement()
                .extracting(ValidationIssue::code)
                .isEqualTo("XML_PROCESSING_ERROR");
        assertThat(namesReport.complete()).isFalse();
        assertThat(namesReport.issues()).singleElement()
                .extracting(ValidationIssue::code)
                .isEqualTo("XML_PROCESSING_ERROR");

        assertThat(deepValidator
                        .withLimits(new ValidationLimits(257, 100))
                        .validate(xml(tooDeep))
                        .valid())
                .isTrue();
        assertThat(namesValidator
                        .withLimits(new ValidationLimits(256, 101))
                        .validate(xml("<root>" + distinctChildren + "</root>"))
                        .complete())
                .isTrue();
    }

    @Test
    void rejectsNonPositiveValidationLimits() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ValidationLimits(0, 100))
                .withMessageContaining("maxElementDepth");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ValidationLimits(256, 0))
                .withMessageContaining("maxDistinctChildNamesPerElement");
    }

    @Test
    void marksMalformedXmlAsIncomplete() throws Exception {
        BetterXsdValidator validator = compile(CHOICE_SCHEMA);

        ValidationReport report = validator.validate(xml("""
                <contact xmlns="urn:contact"><sms>yes</contact>
                """));

        assertThat(report.valid()).isFalse();
        assertThat(report.complete()).isFalse();
        assertThat(report.issues().get(0).code()).isEqualTo("MALFORMED_XML");
    }

    @Test
    void rejectsDoctypesWithoutExpandingEntityValues() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="value" type="xs:string"/>
                </xs:schema>
                """);

        ValidationReport report = validator.validate(xml("""
                <!DOCTYPE value [<!ENTITY private "must-not-appear">]>
                <value>&private;</value>
                """));

        assertThat(report.complete()).isFalse();
        assertThat(report.issues().get(0).code()).isEqualTo("MALFORMED_XML");
        assertThat(report.toString()).doesNotContain("must-not-appear");
    }

    private static BetterXsdValidator compile(String schema)
            throws SchemaCompilationException {
        return BetterXsdValidator.compile(new StreamSource(new StringReader(schema)));
    }

    private static StreamSource xml(String xml) {
        return new StreamSource(new StringReader(xml));
    }
}
