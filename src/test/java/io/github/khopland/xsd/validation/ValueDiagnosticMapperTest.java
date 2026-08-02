package io.github.khopland.xsd.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.khopland.xsd.validation.ValidationObservation.RawDiagnostic;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import javax.xml.namespace.QName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ValueDiagnosticMapperTest {
    private static final QName ATTRIBUTE = new QName("urn:test", "amount");

    @ParameterizedTest(name = "{0} maps to {1}")
    @MethodSource("valueDiagnostics")
    void mapsEveryValueKeyWithoutRetainingSubmittedValues(
            String key,
            String expectedCode,
            Object[] arguments) {
        RawDiagnostic diagnostic = diagnostic(key, arguments);

        DiagnosticIssueBuilder mapped = Objects.requireNonNull(
                ValueDiagnosticMapper.map(diagnostic, ATTRIBUTE));
        ValidationIssue issue = mapped.build();

        assertThat(issue.code()).isEqualTo(expectedCode);
        assertThat(issue.message())
                .contains("@amount")
                .doesNotContain("private-submitted-value");
        assertThat(issue.actualAttribute()).isEqualTo(ATTRIBUTE);
        assertThat(issue.schemaCodes()).containsExactly(key);
        assertThat(ValueDiagnosticMapper.isValueDiagnostic(key)).isTrue();
    }

    @Test
    void dropsUnsafeDatatypeNames() {
        RawDiagnostic diagnostic = diagnostic(
                "cvc-datatype-valid.1.2.1",
                new Object[] {"private-submitted-value", "private type\nsecret"});

        ValidationIssue issue = Objects.requireNonNull(
                        ValueDiagnosticMapper.map(diagnostic, null))
                .build();

        assertThat(issue.message())
                .contains("the declared type")
                .doesNotContain("private", "secret");
    }

    @Test
    void leavesOtherDiagnosticFamiliesForTheirOwnMapper() {
        RawDiagnostic diagnostic = diagnostic("cvc-elt.4.2", new Object[0]);

        assertThat(ValueDiagnosticMapper.map(diagnostic, null)).isNull();
        assertThat(ValueDiagnosticMapper.isValueDiagnostic(diagnostic.key())).isFalse();
    }

    private static Stream<Arguments> valueDiagnostics() {
        return Stream.of(
                Arguments.of(
                        "cvc-datatype-valid.1.2.1",
                        "INVALID_VALUE",
                        new Object[] {"private-submitted-value", "xs:int"}),
                Arguments.of(
                        "cvc-enumeration-valid",
                        "ENUMERATION_VIOLATION",
                        new Object[] {"private-submitted-value", "[one, two]"}),
                Arguments.of(
                        "cvc-pattern-valid",
                        "PATTERN_MISMATCH",
                        new Object[] {"private-submitted-value", "[A-Z]+"}),
                Arguments.of(
                        "cvc-length-valid",
                        "LENGTH_VIOLATION",
                        new Object[] {"private-submitted-value", "8", "3"}),
                Arguments.of(
                        "cvc-minLength-valid",
                        "LENGTH_VIOLATION",
                        new Object[] {"private-submitted-value", "1", "3"}),
                Arguments.of(
                        "cvc-maxLength-valid",
                        "LENGTH_VIOLATION",
                        new Object[] {"private-submitted-value", "8", "3"}),
                Arguments.of(
                        "cvc-minInclusive-valid",
                        "MINIMUM_VIOLATION",
                        new Object[] {"private-submitted-value", "10"}),
                Arguments.of(
                        "cvc-minExclusive-valid",
                        "MINIMUM_VIOLATION",
                        new Object[] {"private-submitted-value", "10"}),
                Arguments.of(
                        "cvc-maxInclusive-valid",
                        "MAXIMUM_VIOLATION",
                        new Object[] {"private-submitted-value", "10"}),
                Arguments.of(
                        "cvc-maxExclusive-valid",
                        "MAXIMUM_VIOLATION",
                        new Object[] {"private-submitted-value", "10"}),
                Arguments.of(
                        "cvc-totalDigits-valid",
                        "TOTAL_DIGITS_EXCEEDED",
                        new Object[] {"private-submitted-value", "8", "3"}),
                Arguments.of(
                        "cvc-fractionDigits-valid",
                        "FRACTION_DIGITS_EXCEEDED",
                        new Object[] {"private-submitted-value", "8", "3"}));
    }

    private static RawDiagnostic diagnostic(String key, Object[] arguments) {
        return new RawDiagnostic(
                "",
                key,
                arguments,
                ValidationSeverity.ERROR,
                "/value[1]",
                1,
                1,
                new QName("value"),
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(ATTRIBUTE));
    }
}
