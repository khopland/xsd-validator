package io.github.khopland.xsd.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.khopland.xsd.validation.ValidationObservation.RawDiagnostic;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import javax.xml.namespace.QName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IdentityDiagnosticMapperTest {
    @ParameterizedTest(name = "{0} maps to {1}")
    @MethodSource("identityDiagnostics")
    void dropsUnsafeConstraintNamesFromPublicIssues(
            String key,
            String expectedCode,
            int constraintArgument) {
        Object[] arguments = new Object[constraintArgument + 1];
        Arrays.fill(arguments, "unused");
        arguments[constraintArgument] = "private constraint\nsecret";
        RawDiagnostic diagnostic = diagnostic(key, arguments);

        DiagnosticIssueBuilder mapped =
                Objects.requireNonNull(IdentityDiagnosticMapper.map(diagnostic));
        ValidationIssue issue = mapped.build();

        assertThat(issue.code()).isEqualTo(expectedCode);
        assertThat(issue.constraintName()).isNull();
        assertThat(issue.message()).doesNotContain("private", "secret");
        assertThat(issue.schemaCodes()).containsExactly(key);
    }

    @Test
    void leavesOtherDiagnosticFamiliesForTheirOwnMapper() {
        assertThat(IdentityDiagnosticMapper.map(
                        diagnostic("cvc-elt.4.2", new Object[0])))
                .isNull();
    }

    private static Stream<Arguments> identityDiagnostics() {
        return Stream.of(
                Arguments.of("DuplicateKey", "DUPLICATE_KEY", 2),
                Arguments.of("DuplicateUnique", "DUPLICATE_UNIQUE", 2),
                Arguments.of("KeyNotFound", "KEY_REFERENCE_NOT_FOUND", 0),
                Arguments.of("AbsentKeyValue", "KEY_VALUE_MISSING", 1),
                Arguments.of("KeyNotEnoughValues", "KEY_VALUE_MISSING", 1));
    }

    private static RawDiagnostic diagnostic(String key, Object[] arguments) {
        return new RawDiagnostic(
                "",
                key,
                arguments,
                ValidationSeverity.ERROR,
                "/records[1]",
                1,
                1,
                new QName("records"),
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of());
    }
}
