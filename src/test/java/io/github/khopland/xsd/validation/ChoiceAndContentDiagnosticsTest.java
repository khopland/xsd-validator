package io.github.khopland.xsd.validation;

import static io.github.khopland.xsd.validation.TestSources.compile;
import static io.github.khopland.xsd.validation.TestSources.xml;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.xml.namespace.QName;
import javax.xml.transform.stream.StreamSource;
import org.apache.xerces.jaxp.validation.XMLSchemaFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChoiceAndContentDiagnosticsTest {
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
        assertThat(issue.path())
                .isEqualTo("/{urn:contact}contact[1]/{urn:contact}sms[1]");
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
    void keepsOverlappingChoiceBranchesCompatible() throws Exception {
        String schema = """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="value">
                    <xs:complexType>
                      <xs:choice>
                        <xs:sequence>
                          <xs:element name="a"/>
                          <xs:element name="b"/>
                        </xs:sequence>
                        <xs:sequence>
                          <xs:element name="c"/>
                          <xs:element name="b"/>
                        </xs:sequence>
                      </xs:choice>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """;
        XercesSchemaCompiler.CompiledSchema compiled = XercesSchemaCompiler.compile(
                new StreamSource(new StringReader(schema)),
                null);

        assertThat(compiled.choiceIndex().match(
                        new QName("value"),
                        new QName("b"),
                        java.util.List.of(
                                new QName("c"))))
                .isEmpty();
        assertThat(compile(schema)
                        .validate(xml("<value><c/><b/></value>"))
                        .valid())
                .isTrue();
    }

    @Test
    void doesNotBorrowChoiceMetadataFromASameNamedElementWithAnotherType()
            throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="root">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="first">
                          <xs:complexType>
                            <xs:sequence>
                              <xs:element name="container">
                                <xs:complexType>
                                  <xs:choice>
                                    <xs:element name="a"/>
                                    <xs:element name="b"/>
                                  </xs:choice>
                                </xs:complexType>
                              </xs:element>
                            </xs:sequence>
                          </xs:complexType>
                        </xs:element>
                        <xs:element name="second">
                          <xs:complexType>
                            <xs:sequence>
                              <xs:element name="container">
                                <xs:complexType>
                                  <xs:sequence>
                                    <xs:element name="a"/>
                                    <xs:element name="c"/>
                                  </xs:sequence>
                                </xs:complexType>
                              </xs:element>
                            </xs:sequence>
                          </xs:complexType>
                        </xs:element>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        ValidationIssue issue = validator.validate(xml("""
                <root>
                  <first><container><a/></container></first>
                  <second><container><a/><b/></container></second>
                </root>
                """)).issues().get(0);

        assertThat(issue.code()).isEqualTo("UNEXPECTED_ELEMENT");
        assertThat(issue.message()).contains("expected <c>");
    }

    @Test
    void createsAnEmptyChoiceIndexForAnEmptyGrammarPool() throws Exception {
        ChoiceIndex choices = ChoiceIndex.from(new XMLSchemaFactory().newSchema(), "");

        assertThat(choices.hasRootLocalName("anything")).isFalse();
        assertThat(choices.match(
                        new QName("value"),
                        new QName("anything"),
                        java.util.List.of()))
                .isEmpty();
    }

    @Test
    void boundsChoiceBranchElementsRenderedInMessages() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="value">
                    <xs:complexType>
                      <xs:choice>
                        <xs:sequence>
                          <xs:element name="one"/>
                          <xs:element name="two"/>
                          <xs:element name="three"/>
                          <xs:element name="four"/>
                          <xs:element name="five"/>
                          <xs:element name="six"/>
                          <xs:element name="seven"/>
                          <xs:element name="eight"/>
                        </xs:sequence>
                        <xs:element name="sms"/>
                      </xs:choice>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        ValidationIssue issue =
                validator.validate(xml("<value><one/><sms/></value>")).issues().get(0);

        assertThat(issue.code()).isEqualTo("CHOICE_ALREADY_SELECTED");
        assertThat(issue.message())
                .contains("<two>", "<six>")
                .doesNotContain("<seven>", "<eight>");
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
        assertThat(issue.path()).isEqualTo("/{urn:contact}contact[1]");
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
    void doesNotApplyLifetimeBranchHistoryToARepeatingChoice() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="values">
                    <xs:complexType>
                      <xs:choice maxOccurs="2">
                        <xs:element name="a"/>
                        <xs:element name="b"/>
                      </xs:choice>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        ValidationIssue issue =
                validator.validate(xml("<values><a/><b/><a/></values>")).issues().get(0);

        assertThat(issue.code()).isNotEqualTo("CHOICE_ALREADY_SELECTED");
        assertThat(issue.message()).doesNotContain("mutually exclusive", "<b> already selected");
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
    void doesNotCallAnAllowedRepeatOutOfOrderADuplicate() throws Exception {
        BetterXsdValidator validator = compile("""
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                  <xs:element name="value">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element name="item" maxOccurs="2"/>
                        <xs:element name="after"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);

        ValidationIssue issue =
                validator.validate(xml("<value><item/><after/><item/></value>"))
                        .issues()
                        .get(0);

        assertThat(issue.code()).isEqualTo("UNEXPECTED_ELEMENT");
        assertThat(issue.message()).doesNotContain("cannot occur again");
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
        assertThat(issue.actualElement()).isEqualTo(new QName("urn:wrong", "contact"));
    }

    @Test
    void rendersNamespacesInPathsForSameNamedElements(@TempDir Path directory)
            throws Exception {
        Files.writeString(directory.resolve("one.xsd"), """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                           targetNamespace="urn:one">
                  <xs:element name="item" type="xs:int"/>
                </xs:schema>
                """);
        Files.writeString(directory.resolve("two.xsd"), """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                           targetNamespace="urn:two">
                  <xs:element name="item" type="xs:int"/>
                </xs:schema>
                """);
        Path root = directory.resolve("root.xsd");
        Files.writeString(root, """
                <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                           xmlns:one="urn:one"
                           xmlns:two="urn:two"
                           targetNamespace="urn:root"
                           xmlns="urn:root"
                           elementFormDefault="qualified">
                  <xs:import namespace="urn:one" schemaLocation="one.xsd"/>
                  <xs:import namespace="urn:two" schemaLocation="two.xsd"/>
                  <xs:element name="root">
                    <xs:complexType>
                      <xs:sequence>
                        <xs:element ref="one:item"/>
                        <xs:element ref="two:item"/>
                      </xs:sequence>
                    </xs:complexType>
                  </xs:element>
                </xs:schema>
                """);
        BetterXsdValidator validator =
                BetterXsdValidator.compile(new StreamSource(root.toFile()));

        ValidationReport report = validator.validate(xml("""
                <root xmlns="urn:root" xmlns:one="urn:one" xmlns:two="urn:two">
                  <one:item>invalid</one:item>
                  <two:item>invalid</two:item>
                </root>
                """));

        assertThat(report.issues())
                .extracting(ValidationIssue::path)
                .containsExactly(
                        "/{urn:root}root[1]/{urn:one}item[1]",
                        "/{urn:root}root[1]/{urn:two}item[1]");
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

}
