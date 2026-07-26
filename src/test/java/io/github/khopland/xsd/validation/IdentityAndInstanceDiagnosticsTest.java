package io.github.khopland.xsd.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import javax.xml.namespace.QName;
import javax.xml.transform.stream.StreamSource;
import org.junit.jupiter.api.Test;

class IdentityAndInstanceDiagnosticsTest {
    @Test
    void reportsDuplicateKeysAndUniqueValuesByConstraintName() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="records">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="record" maxOccurs="unbounded">
                          <xs:complexType>
                            <xs:attribute name="id" use="required"/>
                            <xs:attribute name="alias" use="required"/>
                          </xs:complexType>
                        </xs:element>
                      </xs:sequence>
                    </xs:complexType>
                    <xs:key name="recordKey">
                      <xs:selector xpath="record"/>
                      <xs:field xpath="@id"/>
                    </xs:key>
                    <xs:unique name="recordAlias">
                      <xs:selector xpath="record"/>
                      <xs:field xpath="@alias"/>
                    </xs:unique>
                  </xs:element>
                </xs:schema>
                """);

        ValidationReport report = validator.validate(xml("""
                <records>
                  <record id="private-key" alias="private-alias"/>
                  <record id="private-key" alias="private-alias"/>
                </records>
                """));

        assertThat(report.issues())
                .extracting(ValidationIssue::code)
                .containsExactlyInAnyOrder("DUPLICATE_KEY", "DUPLICATE_UNIQUE");
        assertThat(report.issues())
                .extracting(ValidationIssue::constraintName)
                .containsExactlyInAnyOrder("recordKey", "recordAlias");
        assertThat(report.toString())
                .doesNotContain("private-key", "private-alias");
    }

    @Test
    void reportsAMissingKeyReferenceWithoutRetainingTheReferenceValue() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="records">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="record" minOccurs="0" maxOccurs="unbounded">
                          <xs:complexType>
                            <xs:attribute name="id" use="required"/>
                          </xs:complexType>
                        </xs:element>
                        <xs:element name="reference" minOccurs="0" maxOccurs="unbounded">
                          <xs:complexType>
                            <xs:attribute name="id" use="required"/>
                          </xs:complexType>
                        </xs:element>
                      </xs:sequence>
                    </xs:complexType>
                    <xs:key name="recordKey">
                      <xs:selector xpath="record"/>
                      <xs:field xpath="@id"/>
                    </xs:key>
                    <xs:keyref name="recordReference" refer="recordKey">
                      <xs:selector xpath="reference"/>
                      <xs:field xpath="@id"/>
                    </xs:keyref>
                  </xs:element>
                </xs:schema>
                """);

        ValidationReport report = validator.validate(xml("""
                <records>
                  <record id="known"/>
                  <reference id="private-reference"/>
                </records>
                """));

        assertThat(report.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("KEY_REFERENCE_NOT_FOUND");
            assertThat(issue.constraintName()).isEqualTo("recordReference");
            assertThat(issue.schemaCodes()).containsExactly("KeyNotFound");
            assertThat(issue.message())
                    .contains("recordReference")
                    .doesNotContain("private-reference");
        });
        assertThat(report.toString()).doesNotContain("private-reference");
    }

    @Test
    void reportsAKeyWithNoSelectedFieldValue() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="records">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="record">
                          <xs:complexType>
                            <xs:attribute name="id"/>
                          </xs:complexType>
                        </xs:element>
                      </xs:sequence>
                    </xs:complexType>
                    <xs:key name="recordKey">
                      <xs:selector xpath="record"/>
                      <xs:field xpath="@id"/>
                    </xs:key>
                  </xs:element>
                </xs:schema>
                """);

        ValidationIssue issue =
                validator.validate(xml("<records><record/></records>")).issues().get(0);

        assertThat(issue.code()).isEqualTo("KEY_VALUE_MISSING");
        assertThat(issue.constraintName()).isEqualTo("recordKey");
        assertThat(issue.schemaCodes()).containsExactly("AbsentKeyValue");
    }

    @Test
    void reportsUnresolvableXsiTypeWithoutRetainingItsQName() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="value" type="xs:string"/>
                </xs:schema>
                """);

        ValidationReport report = validator.validate(xml("""
                <value xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       xmlns:private="urn:private"
                       xsi:type="private:PrivateType"/>
                """));

        assertThat(report.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.code()).isEqualTo("XSI_TYPE_NOT_FOUND");
            assertThat(issue.actualAttribute())
                    .isEqualTo(new QName(
                            "http://www.w3.org/2001/XMLSchema-instance",
                            "type",
                            "xsi"));
            assertThat(issue.message()).contains("@xsi:type").doesNotContain("PrivateType");
        });
        assertThat(report.toString()).doesNotContain("PrivateType", "urn:private");
    }

    @Test
    void reportsInvalidNilUsageByTheXsiAttributeQName() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="value" type="xs:string"/>
                </xs:schema>
                """);

        ValidationIssue issue = validator.validate(xml("""
                <value xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                       xsi:nil="true"/>
                """)).issues().get(0);

        assertThat(issue.code()).isEqualTo("XSI_NIL_NOT_ALLOWED");
        assertThat(issue.actualAttribute())
                .isEqualTo(new QName(
                        "http://www.w3.org/2001/XMLSchema-instance",
                        "nil",
                        "xsi"));
        assertThat(issue.message()).contains("not nillable", "@xsi:nil");
    }

    @Test
    void acceptsSubstitutionMembersAndExplainsAnAbstractHead() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="entry" abstract="true"/>
                  <xs:element name="textEntry" substitutionGroup="entry" type="xs:string"/>
                  <xs:element name="records">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element ref="entry"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        assertThat(validator.validate(xml("""
                <records><textEntry>ok</textEntry></records>
                """)).valid()).isTrue();

        ValidationIssue issue = validator.validate(xml("""
                <records><entry/></records>
                """)).issues().get(0);

        assertThat(issue.code()).isEqualTo("ABSTRACT_ELEMENT_REQUIRES_SUBSTITUTE");
        assertThat(issue.actualElement()).isEqualTo(new QName("entry"));
        assertThat(issue.message()).contains("substitution-group member");
    }

    private static BetterXsdValidator compile(String schema)
            throws SchemaCompilationException {
        return BetterXsdValidator.compile(new StreamSource(new StringReader(schema)));
    }

    private static StreamSource xml(String xml) {
        return new StreamSource(new StringReader(xml));
    }
}
