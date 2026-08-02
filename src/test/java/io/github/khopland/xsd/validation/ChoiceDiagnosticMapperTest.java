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
import org.junit.jupiter.params.provider.ValueSource;
import org.jspecify.annotations.Nullable;

class ChoiceDiagnosticMapperTest {
    private static final QName CONTACT = new QName("urn:contact", "contact");
    private static final QName POSTAL_ADDRESS =
            new QName("urn:contact", "postalAddress");
    private static final QName POSTAL_CODE = new QName("urn:contact", "postalCode");
    private static final QName SMS = new QName("urn:contact", "sms");

    private static ChoiceIndex choices;

    @BeforeAll
    static void compileSchema() throws SchemaCompilationException {
        choices = XercesSchemaCompiler.compile(
                new StreamSource(new StringReader(TestSources.CHOICE_SCHEMA)),
                null).choiceIndex();
    }

    @ParameterizedTest
    @ValueSource(strings = {"cvc-complex-type.2.4.a", "cvc-complex-type.2.4.d"})
    void mapsAnElementFromACompetingChoiceBranch(String key) {
        RawDiagnostic diagnostic = diagnostic(
                key,
                new Object[0],
                SMS,
                CONTACT,
                List.of(new SeenElement(POSTAL_ADDRESS, 3)),
                List.of());

        ValidationIssue issue = Objects.requireNonNull(
                        ChoiceDiagnosticMapper.map(diagnostic, choices))
                .build();

        assertThat(issue.code()).isEqualTo("CHOICE_ALREADY_SELECTED");
        assertThat(issue.message()).isEqualTo(
                "<sms> cannot occur here: <postalAddress> at line 3 already selected "
                        + "a mutually exclusive choice. Complete that branch with "
                        + "<postalCode>, or remove it before using <sms>.");
        assertThat(issue.actualElement()).isEqualTo(SMS);
        assertThat(issue.schemaCodes()).containsExactly(key);
    }

    @Test
    void mapsAnIncompleteSelectedChoiceBranch() {
        RawDiagnostic diagnostic = diagnostic(
                "cvc-complex-type.2.4.b",
                new Object[] {"unused", "{\"urn:contact\":postalCode}"},
                CONTACT,
                null,
                List.of(),
                List.of(new SeenElement(POSTAL_ADDRESS, 4)));

        ValidationIssue issue = Objects.requireNonNull(
                        ChoiceDiagnosticMapper.map(diagnostic, choices))
                .build();

        assertThat(issue.code()).isEqualTo("CHOICE_BRANCH_INCOMPLETE");
        assertThat(issue.message()).isEqualTo(
                "<postalAddress> at line 4 selected a choice branch that is incomplete. "
                        + "Add <postalCode> before <contact> closes.");
        assertThat(issue.expectedElements()).containsExactly(POSTAL_CODE);
        assertThat(issue.schemaCodes()).containsExactly("cvc-complex-type.2.4.b");
    }

    @Test
    void leavesUnrelatedDiagnosticsForOtherMappers() {
        assertThat(ChoiceDiagnosticMapper.map(
                        diagnostic(
                                "cvc-complex-type.2.4.e",
                                new Object[0],
                                SMS,
                                CONTACT,
                                List.of(new SeenElement(POSTAL_ADDRESS, 3)),
                                List.of()),
                        choices))
                .isNull();
    }

    @Test
    void leavesChoiceDiagnosticsWithoutMatchingHistoryForContentMapping() {
        assertThat(Stream.of("cvc-complex-type.2.4.a", "cvc-complex-type.2.4.b")
                .map(key -> diagnostic(
                        key,
                        new Object[0],
                        SMS,
                        CONTACT,
                        List.of(),
                        List.of()))
                .map(diagnostic -> ChoiceDiagnosticMapper.map(diagnostic, choices)))
                .allMatch(Objects::isNull);
    }

    private static RawDiagnostic diagnostic(
            String key,
            Object[] arguments,
            QName actualElement,
            @Nullable QName parentElement,
            List<SeenElement> previousSiblings,
            List<SeenElement> children) {
        return new RawDiagnostic(
                "",
                key,
                arguments,
                ValidationSeverity.ERROR,
                "/contact[1]",
                1,
                1,
                actualElement,
                parentElement,
                null,
                null,
                previousSiblings,
                children,
                List.of());
    }
}
