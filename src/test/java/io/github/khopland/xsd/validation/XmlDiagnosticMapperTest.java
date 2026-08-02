package io.github.khopland.xsd.validation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.khopland.xsd.validation.ValidationObservation.RawDiagnostic;
import java.io.StringReader;
import java.util.List;
import java.util.Objects;
import javax.xml.namespace.QName;
import javax.xml.transform.stream.StreamSource;
import org.junit.jupiter.api.Test;
import org.jspecify.annotations.Nullable;

class XmlDiagnosticMapperTest {
    @Test
    void distinguishesKnownRootsInTheWrongNamespace() throws Exception {
        XercesSchemaCompiler.CompiledSchema compiled = compiledSchema();
        RawDiagnostic diagnostic = diagnostic(
                "cvc-elt.1.a",
                ValidationSeverity.ERROR,
                new QName("urn:wrong", "contact"));

        ValidationIssue issue = Objects.requireNonNull(XmlDiagnosticMapper.map(
                        diagnostic,
                        compiled.identity(),
                        compiled.choiceIndex()))
                .build();

        assertThat(issue.code()).isEqualTo("ROOT_NAMESPACE_MISMATCH");
        assertThat(issue.message()).contains("urn:wrong", "urn:contact");
        assertThat(issue.schemaCodes()).containsExactly("cvc-elt.1.a");
    }

    @Test
    void leavesUnknownRootNamesAsUndeclared() throws Exception {
        XercesSchemaCompiler.CompiledSchema compiled = compiledSchema();
        RawDiagnostic diagnostic = diagnostic(
                "cvc-elt.1.a",
                ValidationSeverity.ERROR,
                new QName("urn:wrong", "unrelated"));

        ValidationIssue issue = Objects.requireNonNull(XmlDiagnosticMapper.map(
                        diagnostic,
                        compiled.identity(),
                        compiled.choiceIndex()))
                .build();

        assertThat(issue.code()).isEqualTo("UNDECLARED_ROOT");
        assertThat(issue.message()).doesNotContain("schema expects", "namespace mismatch");
        assertThat(issue.schemaCodes()).containsExactly("cvc-elt.1.a");
    }

    @Test
    void mapsProcessingFailuresBeforeTheFatalFallback() throws Exception {
        XercesSchemaCompiler.CompiledSchema compiled = compiledSchema();
        RawDiagnostic diagnostic = diagnostic(
                "xml-processing-stopped",
                ValidationSeverity.FATAL,
                null);

        ValidationIssue issue = Objects.requireNonNull(XmlDiagnosticMapper.map(
                        diagnostic,
                        compiled.identity(),
                        compiled.choiceIndex()))
                .build();

        assertThat(issue.code()).isEqualTo("XML_PROCESSING_ERROR");
        assertThat(issue.schemaCodes()).containsExactly("xml-processing-stopped");
    }

    @Test
    void leavesOtherDiagnosticFamiliesForTheirOwnMapper() throws Exception {
        XercesSchemaCompiler.CompiledSchema compiled = compiledSchema();
        RawDiagnostic diagnostic = diagnostic(
                "cvc-elt.4.2",
                ValidationSeverity.ERROR,
                new QName("contact"));

        assertThat(XmlDiagnosticMapper.map(
                        diagnostic,
                        compiled.identity(),
                        compiled.choiceIndex()))
                .isNull();
    }

    private static XercesSchemaCompiler.CompiledSchema compiledSchema()
            throws SchemaCompilationException {
        return XercesSchemaCompiler.compile(
                new StreamSource(new StringReader("""
                        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                                   targetNamespace="urn:contact"
                                   xmlns="urn:contact"
                                   elementFormDefault="qualified">
                          <xs:element name="contact" type="xs:string"/>
                        </xs:schema>
                        """)),
                null);
    }

    private static RawDiagnostic diagnostic(
            String key,
            ValidationSeverity severity,
            @Nullable QName actualElement) {
        return new RawDiagnostic(
                "",
                key,
                new Object[0],
                severity,
                "/contact[1]",
                1,
                1,
                actualElement,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of());
    }
}
