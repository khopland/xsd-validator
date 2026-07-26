package io.github.khopland.xsd.validation;

import static io.github.khopland.xsd.validation.TestSources.compile;
import static io.github.khopland.xsd.validation.TestSources.xml;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.khopland.xsd.validation.ValidationObservation.RawDiagnostic;
import java.io.StringReader;
import javax.xml.namespace.QName;
import javax.xml.transform.stream.StreamSource;
import org.junit.jupiter.api.Test;

class ValueAndAttributeDiagnosticsTest {
    @Test
    void groupsDatatypeEventsWithoutRetainingTheLexicalValue() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="age" type="xs:int"/>
                </xs:schema>
                """);

        ValidationReport report = validator.validate(xml("<age>secret-123</age>"));

        assertThat(report.rawEventCount()).isEqualTo(2);
        assertThat(report.issues()).hasSize(1);
        ValidationIssue issue = report.issues().get(0);
        assertThat(issue.code()).isEqualTo("INVALID_VALUE");
        assertThat(issue.schemaCodes())
                .containsExactly("cvc-datatype-valid.1.2.1", "cvc-type.3.1.3");
        assertThat(issue.message()).contains("int").doesNotContain("secret-123");
        assertThat(report.toString()).doesNotContain("secret-123");
    }

    @Test
    void previewsLongEnumerationsWithoutRetainingTheSubmittedValue() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:simpleType name="Role">
                    <xs:restriction base="xs:string">
                      <xs:enumeration value="one"/>
                      <xs:enumeration value="two"/>
                      <xs:enumeration value="three"/>
                      <xs:enumeration value="four"/>
                      <xs:enumeration value="five"/>
                      <xs:enumeration value="six"/>
                      <xs:enumeration value="seven"/>
                      <xs:enumeration value="eight"/>
                      <xs:enumeration value="nine"/>
                      <xs:enumeration value="ten"/>
                      <xs:enumeration value="eleven"/>
                      <xs:enumeration value="twelve"/>
                      <xs:enumeration value="thirteen"/>
                      <xs:enumeration value="fourteen"/>
                      <xs:enumeration value="fifteen"/>
                      <xs:enumeration value="sixteen"/>
                      <xs:enumeration value="seventeen"/>
                      <xs:enumeration value="eighteen"/>
                    </xs:restriction>
                  </xs:simpleType>
                  <xs:element name="role" type="Role"/>
                </xs:schema>
                """);

        ValidationReport report =
                validator.validate(xml("<role>private-submitted-value</role>"));

        assertThat(report.rawEventCount()).isEqualTo(2);
        assertThat(report.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("ENUMERATION_VIOLATION");
            assertThat(issue.message())
                    .contains("‘one’", "‘five’", "and 13 more")
                    .doesNotContain("private-submitted-value");
            assertThat(issue.schemaCodes())
                    .containsExactly("cvc-enumeration-valid", "cvc-type.3.1.3");
        });
        assertThat(report.toString()).doesNotContain("private-submitted-value");
    }

    @Test
    void fallsBackWhenStructuredDiagnosticMappingFails() throws Exception {
        XercesSchemaCompiler.CompiledSchema compiled = XercesSchemaCompiler.compile(
                new StreamSource(new StringReader("""
                        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                          <xs:element name="value" type="xs:string"/>
                        </xs:schema>
                        """)),
                null);
        Object invalidArgument = new Object() {
            @Override
            public String toString() {
                throw new IllegalArgumentException("cannot render");
            }
        };
        RawDiagnostic diagnostic = new RawDiagnostic(
                "",
                "cvc-enumeration-valid",
                new Object[] {null, invalidArgument},
                ValidationSeverity.ERROR,
                "/value[1]",
                1,
                1,
                new QName("value"),
                null,
                null,
                null,
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of());

        assertThat(DiagnosticMapper.map(
                        java.util.List.of(diagnostic),
                        compiled.identity(),
                        compiled.choiceIndex()))
                .singleElement()
                .satisfies(issue -> {
                    assertThat(issue.code()).isEqualTo("SCHEMA_VALIDATION_ERROR");
                    assertThat(issue.schemaCodes()).containsExactly("cvc-enumeration-valid");
                });
    }

    @Test
    void reportsPatternLengthAndNumericFacetsWithoutRawValues() throws Exception {
        BetterXsdValidator patternValidator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:simpleType name="Code">
                    <xs:restriction base="xs:string">
                      <xs:pattern value="[A-Z]{3}"/>
                    </xs:restriction>
                  </xs:simpleType>
                  <xs:element name="code" type="Code"/>
                </xs:schema>
                """);
        BetterXsdValidator lengthValidator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:simpleType name="Code">
                    <xs:restriction base="xs:string">
                      <xs:maxLength value="3"/>
                    </xs:restriction>
                  </xs:simpleType>
                  <xs:element name="code" type="Code"/>
                </xs:schema>
                """);
        BetterXsdValidator minimumValidator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:simpleType name="Amount">
                    <xs:restriction base="xs:decimal">
                      <xs:minInclusive value="10"/>
                    </xs:restriction>
                  </xs:simpleType>
                  <xs:element name="amount" type="Amount"/>
                </xs:schema>
                """);

        ValidationIssue pattern =
                patternValidator.validate(xml("<code>private-pattern</code>")).issues().get(0);
        ValidationIssue length =
                lengthValidator.validate(xml("<code>private-length</code>")).issues().get(0);
        ValidationIssue minimum =
                minimumValidator.validate(xml("<amount>1</amount>")).issues().get(0);

        assertThat(pattern.code()).isEqualTo("PATTERN_MISMATCH");
        assertThat(pattern.message()).doesNotContain("private-pattern");
        assertThat(length.code()).isEqualTo("LENGTH_VIOLATION");
        assertThat(length.message()).contains("length at most 3").doesNotContain("private-length");
        assertThat(minimum.code()).isEqualTo("MINIMUM_VIOLATION");
        assertThat(minimum.message()).contains("at least 10").doesNotContain(">1<");
    }

    @Test
    void reportsBooleanAndDateDatatypeFailuresWithoutRawValues() throws Exception {
        BetterXsdValidator booleanValidator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="enabled" type="xs:boolean"/>
                </xs:schema>
                """);
        BetterXsdValidator dateValidator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="created" type="xs:date"/>
                </xs:schema>
                """);

        ValidationReport invalidBoolean =
                booleanValidator.validate(xml("<enabled>private-boolean</enabled>"));
        ValidationReport invalidDate =
                dateValidator.validate(xml("<created>private-date</created>"));

        assertThat(invalidBoolean.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("INVALID_VALUE");
            assertThat(issue.message()).contains("boolean").doesNotContain("private-boolean");
        });
        assertThat(invalidDate.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("INVALID_VALUE");
            assertThat(issue.message()).contains("date").doesNotContain("private-date");
        });
        assertThat(invalidBoolean.toString()).doesNotContain("private-boolean");
        assertThat(invalidDate.toString()).doesNotContain("private-date");
    }

    @Test
    void reportsRequiredAndUnexpectedAttributesByQName() throws Exception {
        BetterXsdValidator requiredValidator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                           targetNamespace="urn:people"
                           xmlns="urn:people"
                           elementFormDefault="qualified"
                           attributeFormDefault="qualified">
                  <xs:element name="person">
                    <xs:complexType>
                      <xs:attribute name="id" type="xs:string" use="required"/>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);
        BetterXsdValidator unexpectedValidator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="person">
                    <xs:complexType/>
                  </xs:element>
                </xs:schema>
                """);

        ValidationIssue required =
                requiredValidator.validate(xml("<person xmlns=\"urn:people\"/>")).issues().get(0);
        ValidationIssue unexpected = unexpectedValidator
                .validate(xml("<person extra=\"private-value\"/>"))
                .issues()
                .get(0);

        assertThat(required.code()).isEqualTo("REQUIRED_ATTRIBUTE_MISSING");
        assertThat(required.actualAttribute()).isEqualTo(new QName("urn:people", "id"));
        assertThat(required.message()).contains("@id", "<person>");
        assertThat(unexpected.code()).isEqualTo("ATTRIBUTE_NOT_ALLOWED");
        assertThat(unexpected.actualAttribute()).isEqualTo(new QName("extra"));
        assertThat(unexpected.message())
                .contains("@extra", "<person>")
                .doesNotContain("private-value");
    }

    @Test
    void groupsInvalidAttributeFacetEventsAndKeepsTheAttributeQName() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:simpleType name="Role">
                    <xs:restriction base="xs:string">
                      <xs:enumeration value="reader"/>
                      <xs:enumeration value="writer"/>
                    </xs:restriction>
                  </xs:simpleType>
                  <xs:element name="person">
                    <xs:complexType>
                      <xs:attribute name="role" type="Role"/>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        ValidationReport report =
                validator.validate(xml("<person role=\"private-role\"/>"));

        assertThat(report.rawEventCount()).isEqualTo(2);
        assertThat(report.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("ENUMERATION_VIOLATION");
            assertThat(issue.actualAttribute()).isEqualTo(new QName("role"));
            assertThat(issue.message())
                    .contains("@role", "‘reader’", "‘writer’")
                    .doesNotContain("private-role");
            assertThat(issue.schemaCodes())
                    .containsExactly("cvc-enumeration-valid", "cvc-attribute.3");
        });
        assertThat(report.toString()).doesNotContain("private-role");
    }

    @Test
    void groupsFixedAttributeEventsWithoutRetainingTheSubmittedValue() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:attribute name="status" type="xs:string" fixed="active"/>
                  <xs:element name="person">
                    <xs:complexType>
                      <xs:attribute ref="status" fixed="active"/>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        ValidationReport report =
                validator.validate(xml("<person status=\"private-status\"/>"));

        assertThat(report.rawEventCount()).isEqualTo(2);
        assertThat(report.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("ATTRIBUTE_FIXED_VALUE_MISMATCH");
            assertThat(issue.actualAttribute()).isEqualTo(new QName("status"));
            assertThat(issue.message())
                    .contains("@status", "fixed value")
                    .doesNotContain("private-status");
            assertThat(issue.schemaCodes())
                    .containsExactly("cvc-attribute.4", "cvc-complex-type.3.1");
        });
        assertThat(report.toString()).doesNotContain("private-status");
    }

    @Test
    void reportsAProhibitedAttributeWithoutRetainingItsValue() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="person">
                    <xs:complexType>
                      <xs:attribute name="secret" use="prohibited"/>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        ValidationReport report =
                validator.validate(xml("<person secret=\"private-secret\"/>"));

        assertThat(report.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("ATTRIBUTE_NOT_ALLOWED");
            assertThat(issue.actualAttribute()).isEqualTo(new QName("secret"));
            assertThat(issue.message()).contains("@secret", "not allowed");
        });
        assertThat(report.toString()).doesNotContain("private-secret");
    }

}
