package io.github.khopland.xsd.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.khopland.xsd.validation.ValidationObservation.RawDiagnostic;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class InstanceDiagnosticMapperTest {
    @ParameterizedTest(name = "{0} maps to {1}")
    @MethodSource("instanceDiagnostics")
    void mapsEveryInstanceKeyWithoutRetainingArguments(
            String key,
            String expectedCode,
            String expectedAttribute) {
        RawDiagnostic diagnostic = diagnostic(
                key,
                new Object[] {"private-instance-value"});

        DiagnosticIssueBuilder mapped =
                Objects.requireNonNull(InstanceDiagnosticMapper.map(diagnostic));
        ValidationIssue issue = mapped.build();

        assertThat(issue.code()).isEqualTo(expectedCode);
        assertThat(issue.message()).doesNotContain("private-instance-value");
        assertThat(issue.schemaCodes()).containsExactly(key);
        if (expectedAttribute.isEmpty()) {
            assertThat(issue.actualAttribute()).isNull();
        } else {
            assertThat(issue.actualAttribute()).isEqualTo(new QName(
                    XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI,
                    expectedAttribute,
                    "xsi"));
        }
    }

    @Test
    void leavesOtherDiagnosticFamiliesForTheirOwnMapper() {
        assertThat(InstanceDiagnosticMapper.map(
                        diagnostic("DuplicateKey", new Object[0])))
                .isNull();
    }

    private static Stream<Arguments> instanceDiagnostics() {
        return Stream.of(
                Arguments.of(
                        "cvc-elt.2",
                        "ABSTRACT_ELEMENT_REQUIRES_SUBSTITUTE",
                        ""),
                Arguments.of("cvc-elt.4.1", "INVALID_XSI_TYPE", "type"),
                Arguments.of("cvc-elt.4.2", "XSI_TYPE_NOT_FOUND", "type"),
                Arguments.of("cvc-elt.4.3", "XSI_TYPE_NOT_DERIVED", "type"),
                Arguments.of("cvc-elt.3.1", "XSI_NIL_NOT_ALLOWED", "nil"),
                Arguments.of("cvc-elt.3.2.1", "NILLED_ELEMENT_HAS_CONTENT", "nil"),
                Arguments.of(
                        "cvc-elt.3.2.2",
                        "XSI_NIL_FIXED_VALUE_CONFLICT",
                        "nil"));
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
                List.of());
    }
}
