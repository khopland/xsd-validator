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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ContentDiagnosticMapperTest {
    private static ChoiceIndex choices;

    @BeforeAll
    static void compileSchema() throws SchemaCompilationException {
        choices = XercesSchemaCompiler.compile(
                new StreamSource(new StringReader("""
                        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                          <xs:element name="value" type="xs:string"/>
                        </xs:schema>
                        """)),
                null).choiceIndex();
    }

    @ParameterizedTest(name = "{0} maps to {1}")
    @MethodSource("contentDiagnostics")
    void mapsEveryContentKey(
            String key,
            String expectedCode,
            Object[] arguments,
            boolean hasExpectedElement) {
        RawDiagnostic diagnostic = diagnostic(key, arguments);

        ValidationIssue issue = Objects.requireNonNull(
                        ContentDiagnosticMapper.map(diagnostic, choices))
                .build();

        assertThat(issue.code()).isEqualTo(expectedCode);
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
                        new Object[] {"unused", "{item}"},
                        true),
                Arguments.of(
                        "cvc-complex-type.2.4.b",
                        "MISSING_ELEMENT",
                        new Object[] {"unused", "{item}"},
                        true),
                Arguments.of(
                        "cvc-complex-type.2.4.d",
                        "UNEXPECTED_ELEMENT",
                        new Object[0],
                        false),
                Arguments.of(
                        "cvc-complex-type.2.4.e",
                        "MAX_OCCURS_EXCEEDED",
                        new Object[] {"unused", "{item}", "2"},
                        true),
                Arguments.of(
                        "cvc-complex-type.2.4.f",
                        "MAX_OCCURS_EXCEEDED",
                        new Object[] {"unused", "2"},
                        false),
                Arguments.of(
                        "cvc-complex-type.2.4.g",
                        "MIN_OCCURS_NOT_MET",
                        new Object[] {"unused", "{item}"},
                        true),
                Arguments.of(
                        "cvc-complex-type.2.4.h",
                        "MIN_OCCURS_NOT_MET",
                        new Object[] {"unused", "{item}", "unused", "2"},
                        true),
                Arguments.of(
                        "cvc-complex-type.2.4.i",
                        "MIN_OCCURS_NOT_MET",
                        new Object[] {"unused", "{item}"},
                        true),
                Arguments.of(
                        "cvc-complex-type.2.4.j",
                        "MIN_OCCURS_NOT_MET",
                        new Object[] {"unused", "{item}", "unused", "2"},
                        true));
    }

    private static RawDiagnostic diagnostic(String key, Object[] arguments) {
        return new RawDiagnostic(
                "",
                key,
                arguments,
                ValidationSeverity.ERROR,
                "/value[1]/after[1]",
                1,
                1,
                new QName("after"),
                new QName("value"),
                null,
                null,
                List.of(new SeenElement(new QName("item"), 1)),
                List.of(),
                List.of());
    }
}
