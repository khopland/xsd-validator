package io.github.khopland.xsd.validation;

import static io.github.khopland.xsd.validation.TestSources.CHOICE_SCHEMA;
import static io.github.khopland.xsd.validation.TestSources.compile;
import static io.github.khopland.xsd.validation.TestSources.xml;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Golden compatibility corpus for the structured Xerces events on which the
 * public diagnostic contract depends.
 */
class XercesCompatibilityCorpusTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("corpus")
    void preservesDiagnosticSignatures(
            String name,
            String schema,
            String instance,
            int rawEventCount,
            List<String> signatures)
            throws Exception {
        ValidationReport report = compile(schema).validate(xml(instance));

        assertThat(report.rawEventCount()).isEqualTo(rawEventCount);
        assertThat(report.issues().stream()
                        .map(XercesCompatibilityCorpusTest::signature))
                .containsExactlyElementsOf(signatures);
        assertThat(report.toString()).doesNotContain("private");
    }

    private static Stream<Arguments> corpus() {
        return Stream.of(
                Arguments.of(
                        "grouped datatype failure",
                        """
                        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                          <xs:element name="age" type="xs:int"/>
                        </xs:schema>
                        """,
                        "<age>private-value</age>",
                        2,
                        List.of(
                                "ERROR|INVALID_VALUE|/age[1]|-|"
                                        + "cvc-datatype-valid.1.2.1,cvc-type.3.1.3")),
                Arguments.of(
                        "required attribute",
                        """
                        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                          <xs:element name="person">
                            <xs:complexType>
                              <xs:attribute name="id" use="required"/>
                            </xs:complexType>
                          </xs:element>
                        </xs:schema>
                        """,
                        "<person/>",
                        1,
                        List.of(
                                "ERROR|REQUIRED_ATTRIBUTE_MISSING|/person[1]|-|"
                                        + "cvc-complex-type.4")),
                Arguments.of(
                        "selected choice branch",
                        CHOICE_SCHEMA,
                        """
                        <contact xmlns="urn:contact">
                          <postalAddress>private-address</postalAddress>
                          <postalCode>private-code</postalCode>
                          <sms>private-sms</sms>
                        </contact>
                        """,
                        1,
                        List.of(
                                "ERROR|CHOICE_ALREADY_SELECTED|"
                                        + "/{urn:contact}contact[1]/{urn:contact}sms[1]|-|"
                                        + "cvc-complex-type.2.4.d")),
                Arguments.of(
                        "missing required content",
                        """
                        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                          <xs:element name="root">
                            <xs:complexType>
                              <xs:sequence><xs:element name="child"/></xs:sequence>
                            </xs:complexType>
                          </xs:element>
                        </xs:schema>
                        """,
                        "<root/>",
                        1,
                        List.of(
                                "ERROR|MISSING_ELEMENT|/root[1]|-|"
                                        + "cvc-complex-type.2.4.b")),
                Arguments.of(
                        "missing key reference",
                        """
                        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                          <xs:element name="records">
                            <xs:complexType>
                              <xs:sequence>
                                <xs:element name="record" minOccurs="0" maxOccurs="unbounded">
                                  <xs:complexType><xs:attribute name="id"/></xs:complexType>
                                </xs:element>
                                <xs:element name="reference" minOccurs="0" maxOccurs="unbounded">
                                  <xs:complexType><xs:attribute name="id"/></xs:complexType>
                                </xs:element>
                              </xs:sequence>
                            </xs:complexType>
                            <xs:key name="recordKey">
                              <xs:selector xpath="record"/><xs:field xpath="@id"/>
                            </xs:key>
                            <xs:keyref name="recordReference" refer="recordKey">
                              <xs:selector xpath="reference"/><xs:field xpath="@id"/>
                            </xs:keyref>
                          </xs:element>
                        </xs:schema>
                        """,
                        "<records><reference id=\"private-reference\"/></records>",
                        1,
                        List.of(
                                "ERROR|KEY_REFERENCE_NOT_FOUND|/records[1]|recordReference|"
                                        + "KeyNotFound")),
                Arguments.of(
                        "unknown xsi type",
                        """
                        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                          <xs:element name="value" type="xs:string"/>
                        </xs:schema>
                        """,
                        """
                        <value xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                               xmlns:private="urn:private"
                               xsi:type="private:PrivateType"/>
                        """,
                        1,
                        List.of(
                                "ERROR|XSI_TYPE_NOT_FOUND|/value[1]|-|cvc-elt.4.2")));
    }

    private static String signature(ValidationIssue issue) {
        return issue.severity()
                + "|" + issue.code()
                + "|" + issue.path()
                + "|" + (issue.constraintName() == null ? "-" : issue.constraintName())
                + "|" + String.join(",", issue.schemaCodes());
    }
}
