package io.github.khopland.xsd.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.StringReader;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
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
    void explainsASelectedBranchThatIsIncompleteWhenTheParentCloses() throws Exception {
        BetterXsdValidator validator = compile(CHOICE_SCHEMA);

        ValidationIssue issue = validator.validate(xml("""
                <contact xmlns="urn:contact">
                  <postalAddress>Main street</postalAddress>
                </contact>
                """)).issues().get(0);

        assertThat(issue.code()).isEqualTo("CHOICE_BRANCH_INCOMPLETE");
        assertThat(issue.path()).isEqualTo("/contact[1]");
        assertThat(issue.message())
                .contains("<postalAddress>", "Add <postalCode>", "before <contact> closes");
        assertThat(issue.expectedElements())
                .containsExactly(new QName("urn:contact", "postalCode"));
        assertThat(issue.schemaCodes()).containsExactly("cvc-complex-type.2.4.b");
    }

    @Test
    void explainsNamedTypeChoicesWithoutPrescribingOptionalTailElements() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:complexType name="ContactType">
                    <xs:choice>
                      <xs:sequence>
                        <xs:element name="postalAddress"/>
                        <xs:element name="postalCode" minOccurs="0"/>
                      </xs:sequence>
                      <xs:element name="sms"/>
                    </xs:choice>
                  </xs:complexType>
                  <xs:element name="contact" type="ContactType"/>
                </xs:schema>
                """);

        ValidationIssue issue = validator.validate(xml("""
                <contact><postalAddress/><sms/></contact>
                """)).issues().get(0);

        assertThat(issue.code()).isEqualTo("CHOICE_ALREADY_SELECTED");
        assertThat(issue.message())
                .contains("<postalAddress>", "mutually exclusive")
                .doesNotContain("Complete that branch", "<postalCode>");
    }

    @Test
    void explainsChoicesInheritedFromABaseType() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:complexType name="BaseType">
                    <xs:choice>
                      <xs:element name="email"/>
                      <xs:element name="sms"/>
                    </xs:choice>
                  </xs:complexType>
                  <xs:complexType name="ExtendedType">
                    <xs:complexContent>
                      <xs:extension base="BaseType">
                        <xs:sequence>
                          <xs:element name="after" minOccurs="0"/>
                        </xs:sequence>
                      </xs:extension>
                    </xs:complexContent>
                  </xs:complexType>
                  <xs:element name="contact" type="ExtendedType"/>
                </xs:schema>
                """);

        ValidationIssue issue =
                validator.validate(xml("<contact><email/><sms/></contact>")).issues().get(0);

        assertThat(issue.code()).isEqualTo("CHOICE_ALREADY_SELECTED");
        assertThat(issue.message()).contains("<email>", "mutually exclusive");
    }

    @Test
    void explainsChoicesFromImportedTypes(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("types.xsd"), """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                           targetNamespace="urn:types"
                           elementFormDefault="qualified">
                  <xs:complexType name="ContactType">
                    <xs:choice>
                      <xs:element name="email"/>
                      <xs:element name="sms"/>
                    </xs:choice>
                  </xs:complexType>
                </xs:schema>
                """);
        Path root = directory.resolve("root.xsd");
        Files.writeString(root, """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                           xmlns:t="urn:types"
                           targetNamespace="urn:root"
                           xmlns="urn:root"
                           elementFormDefault="qualified">
                  <xs:import namespace="urn:types" schemaLocation="types.xsd"/>
                  <xs:element name="contact" type="t:ContactType"/>
                </xs:schema>
                """);
        BetterXsdValidator validator =
                BetterXsdValidator.compile(new StreamSource(root.toFile()));

        ValidationIssue issue = validator.validate(xml("""
                <contact xmlns="urn:root" xmlns:t="urn:types">
                  <t:email/><t:sms/>
                </contact>
                """)).issues().get(0);

        assertThat(issue.code()).isEqualTo("CHOICE_ALREADY_SELECTED");
        assertThat(issue.actualElement()).isEqualTo(new QName("urn:types", "sms"));
        assertThat(issue.message()).contains("<email>", "mutually exclusive");
    }

    @Test
    void reportsTheElementExpectedBeforeAnOutOfOrderElement() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="values">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="first" type="xs:string"/>
                        <xs:element name="second" type="xs:string"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        ValidationIssue issue = validator.validate(xml("""
                <values><second/></values>
                """)).issues().get(0);

        assertThat(issue.code()).isEqualTo("UNEXPECTED_ELEMENT");
        assertThat(issue.actualElement()).isEqualTo(new QName("second"));
        assertThat(issue.expectedElements()).containsExactly(new QName("first"));
        assertThat(issue.message()).contains("<second>", "expected <first>");
    }

    @Test
    void reportsRequiredContentMissingBeforeTheParentCloses() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="values">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="first" type="xs:string"/>
                        <xs:element name="second" type="xs:string"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        ValidationIssue issue = validator.validate(xml("""
                <values><first/></values>
                """)).issues().get(0);

        assertThat(issue.code()).isEqualTo("MISSING_ELEMENT");
        assertThat(issue.path()).isEqualTo("/values[1]");
        assertThat(issue.expectedElements()).containsExactly(new QName("second"));
        assertThat(issue.message()).contains("add <second> before it closes");
    }

    @Test
    void usesTheEffectiveInheritedOrderReportedByXerces() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:complexType name="BaseType">
                    <xs:sequence>
                      <xs:element name="baseValue" type="xs:string"/>
                    </xs:sequence>
                  </xs:complexType>
                  <xs:complexType name="ExtendedType">
                    <xs:complexContent>
                      <xs:extension base="BaseType">
                        <xs:sequence>
                          <xs:element name="extendedValue" type="xs:string"/>
                        </xs:sequence>
                      </xs:extension>
                    </xs:complexContent>
                  </xs:complexType>
                  <xs:element name="value" type="ExtendedType"/>
                </xs:schema>
                """);

        ValidationIssue issue = validator.validate(xml("""
                <value>
                  <extendedValue/>
                  <baseValue/>
                </value>
                """)).issues().get(0);

        assertThat(issue.code()).isEqualTo("UNEXPECTED_ELEMENT");
        assertThat(issue.actualElement()).isEqualTo(new QName("extendedValue"));
        assertThat(issue.expectedElements()).containsExactly(new QName("baseValue"));
        assertThat(issue.message()).contains("expected <baseValue>");
    }

    @Test
    void boundsTheExpectedElementPreview() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="value">
                    <xs:complexType>
                      <xs:choice>
                        <xs:element name="one"/>
                        <xs:element name="two"/>
                        <xs:element name="three"/>
                        <xs:element name="four"/>
                        <xs:element name="five"/>
                        <xs:element name="six"/>
                        <xs:element name="seven"/>
                        <xs:element name="eight"/>
                      </xs:choice>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        ValidationIssue issue = validator.validate(xml("<value/>")).issues().get(0);

        assertThat(issue.expectedElements())
                .extracting(QName::getLocalPart)
                .containsExactly("one", "two", "three", "four", "five");
        assertThat(issue.message()).contains("and 3 more");
    }

    @Test
    void distinguishesAnExceededMaximumOccurrence() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="value">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="item" maxOccurs="2"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        ValidationIssue issue = validator
                .validate(xml("<value><item/><item/><item/></value>"))
                .issues()
                .get(0);

        assertThat(issue.code()).isEqualTo("MAX_OCCURS_EXCEEDED");
        assertThat(issue.actualElement()).isEqualTo(new QName("item"));
        assertThat(issue.message()).contains("maximum occurrence of 2");
        assertThat(issue.schemaCodes()).containsExactly("cvc-complex-type.2.4.f");
    }

    @Test
    void distinguishesADuplicateAfterACompletedSingleOccurrence() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="value">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="item"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        ValidationIssue issue =
                validator.validate(xml("<value><item/><item/></value>")).issues().get(0);

        assertThat(issue.code()).isEqualTo("DUPLICATE_ELEMENT");
        assertThat(issue.message()).contains("<item>", "cannot occur again");
        assertThat(issue.schemaCodes()).containsExactly("cvc-complex-type.2.4.d");
    }

    @Test
    void distinguishesAnUnmetMinimumOccurrenceAtClose() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="value">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="item" minOccurs="3" maxOccurs="3"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        ValidationIssue issue =
                validator.validate(xml("<value><item/></value>")).issues().get(0);

        assertThat(issue.code()).isEqualTo("MIN_OCCURS_NOT_MET");
        assertThat(issue.path()).isEqualTo("/value[1]");
        assertThat(issue.expectedElements()).containsExactly(new QName("item"));
        assertThat(issue.message()).contains("add 2 more occurrences of <item>");
        assertThat(issue.schemaCodes()).containsExactly("cvc-complex-type.2.4.j");
    }

    @Test
    void distinguishesContentThatArrivesBeforeMinimumOccurrencesAreMet() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="value">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="item" minOccurs="3" maxOccurs="3"/>
                        <xs:element name="after"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        ValidationIssue issue =
                validator.validate(xml("<value><item/><after/></value>")).issues().get(0);

        assertThat(issue.code()).isEqualTo("MIN_OCCURS_NOT_MET");
        assertThat(issue.actualElement()).isEqualTo(new QName("after"));
        assertThat(issue.expectedElements()).containsExactly(new QName("item"));
        assertThat(issue.message())
                .contains("<after>", "add 2 more occurrences of <item> first");
        assertThat(issue.schemaCodes()).containsExactly("cvc-complex-type.2.4.h");
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
    void doesNotCallAnUnrelatedRootANamespaceMismatch() throws Exception {
        BetterXsdValidator validator = compile(CHOICE_SCHEMA);

        ValidationIssue issue = validator.validate(xml("""
                <unrelated xmlns="urn:actual"/>
                """)).issues().get(0);

        assertThat(issue.code()).isEqualTo("UNDECLARED_ROOT");
        assertThat(issue.message()).doesNotContain("schema expects", "namespace mismatch");
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
                        XercesValidationSession.class))
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
        String deepXml = "<node>".repeat(300) + "</node>".repeat(300);

        assertThat(largeValidator.validate(xml(largeXml)).valid()).isTrue();
        assertThat(deepValidator.validate(xml(deepXml)).valid()).isTrue();
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

    private static BetterXsdValidator compile(String schema)
            throws SchemaCompilationException {
        return BetterXsdValidator.compile(new StreamSource(new StringReader(schema)));
    }

    private static StreamSource xml(String xml) {
        return new StreamSource(new StringReader(xml));
    }
}
