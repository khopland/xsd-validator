package io.github.khopland.xsd.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.khopland.xsd.validation.ValidationObservation.RawDiagnostic;
import io.github.khopland.xsd.validation.ValidationObservation.SeenElement;
import java.io.StringReader;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import javax.xml.namespace.QName;
import javax.xml.transform.stream.StreamSource;
import org.apache.xerces.jaxp.validation.XSGrammarPoolContainer;
import org.apache.xerces.xni.grammars.Grammar;
import org.apache.xerces.xni.grammars.XMLGrammarDescription;
import org.apache.xerces.xni.grammars.XSGrammar;
import org.apache.xerces.xs.XSConstants;
import org.apache.xerces.xs.XSElementDeclaration;
import org.apache.xerces.xs.XSModel;
import org.apache.xerces.xs.XSTypeDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.jspecify.annotations.Nullable;

class ContentDiagnosticMapperTest {
    private static ChoiceIndex choices;
    private static XSTypeDefinition parentType;

    @BeforeAll
    static void compileSchema() throws SchemaCompilationException {
        XercesSchemaCompiler.CompiledSchema compiled = XercesSchemaCompiler.compile(
                new StreamSource(new StringReader("""
                        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                          <xs:element name="value">
                            <xs:complexType>
                              <xs:sequence>
                                <xs:element name="item" maxOccurs="2"/>
                              </xs:sequence>
                            </xs:complexType>
                          </xs:element>
                        </xs:schema>
                        """)),
                null);
        choices = compiled.choiceIndex();
        Grammar[] grammars = ((XSGrammarPoolContainer) compiled.schema())
                .getGrammarPool()
                .retrieveInitialGrammarSet(XMLGrammarDescription.XML_SCHEMA);
        XSGrammar[] schemaGrammars = new XSGrammar[grammars.length];
        for (int index = 0; index < grammars.length; index++) {
            schemaGrammars[index] = (XSGrammar) grammars[index];
        }
        XSModel model = schemaGrammars[0].toXSModel(schemaGrammars);
        XSElementDeclaration value = (XSElementDeclaration) model
                .getComponents(XSConstants.ELEMENT_DECLARATION)
                .itemByName(null, "value");
        parentType = value.getTypeDefinition();
    }

    @ParameterizedTest(name = "{0} maps to {1}")
    @MethodSource("contentDiagnostics")
    void mapsEveryContentKey(
            String key,
            String expectedCode,
            String expectedMessage,
            Object[] arguments,
            boolean hasExpectedElement) {
        RawDiagnostic diagnostic = diagnostic(key, arguments);

        ValidationIssue issue = Objects.requireNonNull(
                        ContentDiagnosticMapper.map(diagnostic, choices))
                .build();

        assertThat(issue.code()).isEqualTo(expectedCode);
        assertThat(issue.message()).isEqualTo(expectedMessage);
        assertThat(issue.actualElement()).isEqualTo(new QName("after"));
        assertThat(issue.schemaCodes()).containsExactly(key);
        if (hasExpectedElement) {
            assertThat(issue.expectedElements()).containsExactly(new QName("item"));
        } else {
            assertThat(issue.expectedElements()).isEmpty();
        }
    }

    @Test
    void boundsExpectedElementsAndDropsUnsafeNames() {
        RawDiagnostic diagnostic = diagnostic(
                "cvc-complex-type.2.4.a",
                new Object[] {
                    "unused",
                    "{one, two, three, four, five, six, private\nsecret}"
                });

        ValidationIssue issue = Objects.requireNonNull(
                        ContentDiagnosticMapper.map(diagnostic, choices))
                .build();

        assertThat(issue.expectedElements())
                .extracting(QName::getLocalPart)
                .containsExactly("one", "two", "three", "four", "five");
        assertThat(issue.message())
                .contains("and 1 more")
                .doesNotContain("private", "secret");
    }

    @Test
    void parsesQualifiedExpectedElementNames() {
        RawDiagnostic diagnostic = diagnostic(
                "cvc-complex-type.2.4.a",
                new Object[] {"unused", "{\"urn:item\":child}"});

        ValidationIssue issue = Objects.requireNonNull(
                        ContentDiagnosticMapper.map(diagnostic, choices))
                .build();

        assertThat(issue.expectedElements())
                .containsExactly(new QName("urn:item", "child"));
    }

    @Test
    void classifiesElementsBeyondTheirMaximumAsDuplicates() {
        RawDiagnostic diagnostic = diagnostic(
                "cvc-complex-type.2.4.d",
                new Object[0],
                new QName("item"),
                parentType,
                List.of(
                        new SeenElement(new QName("item"), 1),
                        new SeenElement(new QName("item"), 2)));

        ValidationIssue issue = Objects.requireNonNull(
                        ContentDiagnosticMapper.map(diagnostic, choices))
                .build();

        assertThat(issue.code()).isEqualTo("DUPLICATE_ELEMENT");
        assertThat(issue.message()).contains("<item>", "cannot occur again");
    }

    @Test
    void leavesUnknownKeysForFallbackMapping() {
        assertThat(ContentDiagnosticMapper.map(
                        diagnostic("cvc-complex-type.2.4.z", new Object[0]),
                        choices))
                .isNull();
        assertThat(ContentDiagnosticMapper.map(
                        diagnostic("cvc-elt.4.2", new Object[0]),
                        choices))
                .isNull();
    }

    private static Stream<Arguments> contentDiagnostics() {
        return Stream.of(
                Arguments.of(
                        "cvc-complex-type.2.4.a",
                        "UNEXPECTED_ELEMENT",
                        "Element <after> is not permitted here; expected <item>.",
                        new Object[] {"unused", "{item}"},
                        true),
                Arguments.of(
                        "cvc-complex-type.2.4.b",
                        "MISSING_ELEMENT",
                        "Element <after> is incomplete; add <item> before it closes.",
                        new Object[] {"unused", "{item}"},
                        true),
                Arguments.of(
                        "cvc-complex-type.2.4.d",
                        "UNEXPECTED_ELEMENT",
                        "Element <after> is not permitted at this position.",
                        new Object[0],
                        false),
                Arguments.of(
                        "cvc-complex-type.2.4.e",
                        "MAX_OCCURS_EXCEEDED",
                        "Element <after> exceeds its maximum occurrence of 2; "
                                + "expected <item> instead.",
                        new Object[] {"unused", "{item}", "2"},
                        true),
                Arguments.of(
                        "cvc-complex-type.2.4.f",
                        "MAX_OCCURS_EXCEEDED",
                        "Element <after> exceeds its maximum occurrence of 2.",
                        new Object[] {"unused", "2"},
                        false),
                Arguments.of(
                        "cvc-complex-type.2.4.g",
                        "MIN_OCCURS_NOT_MET",
                        "Element <after> occurs too early; add <item> first.",
                        new Object[] {"unused", "{item}"},
                        true),
                Arguments.of(
                        "cvc-complex-type.2.4.h",
                        "MIN_OCCURS_NOT_MET",
                        "Element <after> occurs too early; add 2 more occurrences of "
                                + "<item> first.",
                        new Object[] {"unused", "{item}", "unused", "2"},
                        true),
                Arguments.of(
                        "cvc-complex-type.2.4.i",
                        "MIN_OCCURS_NOT_MET",
                        "Element <after> is incomplete; add <item> before it closes.",
                        new Object[] {"unused", "{item}"},
                        true),
                Arguments.of(
                        "cvc-complex-type.2.4.j",
                        "MIN_OCCURS_NOT_MET",
                        "Element <after> is incomplete; add 2 more occurrences of "
                                + "<item> before it closes.",
                        new Object[] {"unused", "{item}", "unused", "2"},
                        true));
    }

    private static RawDiagnostic diagnostic(String key, Object[] arguments) {
        return diagnostic(
                key,
                arguments,
                new QName("after"),
                null,
                List.of(new SeenElement(new QName("item"), 1)));
    }

    private static RawDiagnostic diagnostic(
            String key,
            Object[] arguments,
            QName actualElement,
            @Nullable XSTypeDefinition actualParentType,
            List<SeenElement> previousSiblings) {
        return new RawDiagnostic(
                "",
                key,
                arguments,
                ValidationSeverity.ERROR,
                "/value[1]/after[1]",
                1,
                1,
                actualElement,
                new QName("value"),
                null,
                actualParentType,
                previousSiblings,
                List.of(),
                List.of());
    }
}
