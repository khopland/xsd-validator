package io.github.khopland.xsd.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.transform.stream.StreamSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BetterXsdValidatorTest {
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
    void validatesAnOptionalChoiceWhenNeitherBranchIsPresent() throws Exception {
        BetterXsdValidator validator = compile(CHOICE_SCHEMA);

        ValidationReport report = validator.validate(xml("""
                <contact xmlns="urn:contact"/>
                """));

        assertThat(report.valid()).isTrue();
        assertThat(report.complete()).isTrue();
        assertThat(report.rawEventCount()).isZero();
        assertThat(report.schema().targetNamespace()).isEqualTo("urn:contact");
    }

    @Test
    void explainsWhenAnEarlierSiblingSelectedAnotherChoiceBranch() throws Exception {
        BetterXsdValidator validator = compile(CHOICE_SCHEMA);

        ValidationReport report = validator.validate(xml("""
                <contact xmlns="urn:contact">
                  <postalAddress>Main street</postalAddress>
                  <postalCode>1234</postalCode>
                  <sms>yes</sms>
                </contact>
                """));

        assertThat(report.valid()).isFalse();
        ValidationIssue issue = report.issues().get(0);
        assertThat(issue.code())
                .withFailMessage(report::toString)
                .isEqualTo("CHOICE_ALREADY_SELECTED");
        assertThat(issue.path()).isEqualTo("/contact[1]/sms[1]");
        assertThat(issue.message()).contains("<postalAddress>", "mutually exclusive");
        assertThat(issue.schemaCodes()).contains("cvc-complex-type.2.4.d");
    }

    @Test
    void explainsHowToCompleteASelectedMultiElementBranch() throws Exception {
        BetterXsdValidator validator = compile(CHOICE_SCHEMA);

        ValidationIssue issue = validator.validate(xml("""
                <contact xmlns="urn:contact">
                  <postalAddress>Main street</postalAddress>
                  <sms>yes</sms>
                </contact>
                """)).issues().get(0);

        assertThat(issue.code()).isEqualTo("CHOICE_ALREADY_SELECTED");
        assertThat(issue.message())
                .contains("Complete that branch with <postalCode>", "before using <sms>");
        assertThat(issue.schemaCodes()).contains("cvc-complex-type.2.4.a");
    }

    @Test
    void reportsTheExpectedNamespaceDirectly() throws Exception {
        BetterXsdValidator validator = compile(CHOICE_SCHEMA);

        ValidationReport report = validator.validate(xml("""
                <contact xmlns="urn:wrong"/>
                """));

        ValidationIssue issue = report.issues().get(0);
        assertThat(issue.code()).isEqualTo("ROOT_NAMESPACE_MISMATCH");
        assertThat(issue.message()).contains("urn:wrong", "urn:contact");
        assertThat(issue.actualElement().getNamespaceURI()).isEqualTo("urn:wrong");
    }

    @Test
    void distinguishesAnUnknownRootFromAWrongNamespace() throws Exception {
        BetterXsdValidator validator = compile(CHOICE_SCHEMA);

        ValidationIssue issue = validator.validate(xml("""
                <unknown xmlns="urn:contact"/>
                """)).issues().get(0);

        assertThat(issue.code()).isEqualTo("UNDECLARED_ROOT");
        assertThat(issue.message()).doesNotContain("uses namespace");
    }

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

    private static BetterXsdValidator compile(String schema)
            throws SchemaCompilationException {
        return BetterXsdValidator.compile(new StreamSource(new StringReader(schema)));
    }

    private static StreamSource xml(String xml) {
        return new StreamSource(new StringReader(xml));
    }
}
