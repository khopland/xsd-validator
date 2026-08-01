package io.github.khopland.xsd.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.khopland.xsd.validation.ValidationObservation.RawDiagnostic;
import java.util.List;
import java.util.Objects;
import javax.xml.namespace.QName;
import org.junit.jupiter.api.Test;

class IdentityDiagnosticMapperTest {
    @Test
    void dropsAnUnsafeConstraintNameFromThePublicIssue() {
        RawDiagnostic diagnostic = diagnostic(
                "DuplicateKey",
                new Object[] {"private-value", "unused", "private constraint\nsecret"});

        DiagnosticIssueBuilder mapped =
                Objects.requireNonNull(IdentityDiagnosticMapper.map(diagnostic));
        ValidationIssue issue = mapped.build();

        assertThat(issue.code()).isEqualTo("DUPLICATE_KEY");
        assertThat(issue.constraintName()).isNull();
        assertThat(issue.message()).doesNotContain("private", "secret");
        assertThat(issue.schemaCodes()).containsExactly("DuplicateKey");
    }

    @Test
    void leavesOtherDiagnosticFamiliesForTheirOwnMapper() {
        assertThat(IdentityDiagnosticMapper.map(
                        diagnostic("cvc-elt.4.2", new Object[0])))
                .isNull();
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
