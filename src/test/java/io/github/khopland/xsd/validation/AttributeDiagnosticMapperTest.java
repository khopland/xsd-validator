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

class AttributeDiagnosticMapperTest {
    private static final QName ATTRIBUTE = new QName("urn:test", "status");

    @ParameterizedTest(name = "{0} maps to {1}")
    @MethodSource("attributeDiagnostics")
    void mapsEveryAttributeKeyWithoutRetainingUnsafeTypeNames(
            String key,
            String expectedCode) {
        RawDiagnostic diagnostic = diagnostic(
                key,
                new Object[] {"unused", "status", "urn:test", "private type\nsecret"},
                List.of(ATTRIBUTE));

        DiagnosticIssueBuilder mapped = Objects.requireNonNull(
                AttributeDiagnosticMapper.map(diagnostic, ATTRIBUTE));
        ValidationIssue issue = mapped.build();

        assertThat(issue.code()).isEqualTo(expectedCode);
        assertThat(issue.message()).doesNotContain("private", "secret");
        assertThat(issue.actualAttribute()).isEqualTo(ATTRIBUTE);
        assertThat(issue.schemaCodes()).containsExactly(key);
    }

    @Test
    void resolvesNamespacedAttributesFromObservedAttributes() {
        RawDiagnostic diagnostic = diagnostic(
                "cvc-attribute.3",
                new Object[] {"unused", "p:status"},
                List.of(ATTRIBUTE));

        assertThat(AttributeDiagnosticMapper.attributeName(diagnostic))
                .isEqualTo(ATTRIBUTE);
    }

    @Test
    void dropsUnsafeAttributeNames() {
        RawDiagnostic diagnostic = diagnostic(
                "cvc-attribute.3",
                new Object[] {"unused", "private\nattribute"},
                List.of());

        assertThat(AttributeDiagnosticMapper.attributeName(diagnostic)).isNull();
    }

    @Test
    void leavesOtherDiagnosticFamiliesForTheirOwnMapper() {
        RawDiagnostic diagnostic = diagnostic(
                "cvc-elt.4.2",
                new Object[0],
                List.of());

        assertThat(AttributeDiagnosticMapper.map(diagnostic, null)).isNull();
        assertThat(AttributeDiagnosticMapper.attributeName(diagnostic)).isNull();
    }

    private static Stream<Arguments> attributeDiagnostics() {
        return Stream.of(
                Arguments.of("cvc-complex-type.4", "REQUIRED_ATTRIBUTE_MISSING"),
                Arguments.of("cvc-complex-type.4_ns", "REQUIRED_ATTRIBUTE_MISSING"),
                Arguments.of("cvc-complex-type.3.2.1", "ATTRIBUTE_NOT_ALLOWED"),
                Arguments.of("cvc-complex-type.3.2.2", "ATTRIBUTE_NOT_ALLOWED"),
                Arguments.of("cvc-attribute.3", "INVALID_ATTRIBUTE_VALUE"),
                Arguments.of("cvc-attribute.4", "ATTRIBUTE_FIXED_VALUE_MISMATCH"),
                Arguments.of("cvc-complex-type.3.1", "ATTRIBUTE_FIXED_VALUE_MISMATCH"));
    }

    private static RawDiagnostic diagnostic(
            String key,
            Object[] arguments,
            List<QName> attributes) {
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
                attributes);
    }
}
