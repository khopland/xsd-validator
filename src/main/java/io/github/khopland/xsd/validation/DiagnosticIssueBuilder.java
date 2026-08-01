package io.github.khopland.xsd.validation;

import java.util.List;
import javax.xml.namespace.QName;
import org.jspecify.annotations.Nullable;

/** Mutable assembly state used only while one engine diagnostic is mapped. */
final class DiagnosticIssueBuilder {
    private final ValidationSeverity severity;
    private final String code;
    private final String message;
    private final String path;
    private final int line;
    private final int column;
    private final @Nullable QName actualElement;
    private final @Nullable QName actualAttribute;
    private @Nullable String constraintName;
    private final List<QName> expectedElements;
    private final List<String> schemaCodes;

    DiagnosticIssueBuilder(
            ValidationSeverity severity,
            String code,
            String message,
            String path,
            int line,
            int column,
            @Nullable QName actualElement,
            @Nullable QName actualAttribute,
            @Nullable String constraintName,
            List<QName> expectedElements,
            List<String> schemaCodes) {
        this.severity = severity;
        this.code = code;
        this.message = message;
        this.path = path;
        this.line = line;
        this.column = column;
        this.actualElement = actualElement;
        this.actualAttribute = actualAttribute;
        this.constraintName = constraintName;
        this.expectedElements = expectedElements;
        this.schemaCodes = schemaCodes;
    }

    void addSchemaCode(String schemaCode) {
        schemaCodes.add(schemaCode);
    }

    void constraintName(@Nullable String value) {
        constraintName = value;
    }

    ValidationIssue build() {
        return new ValidationIssue(
                severity,
                code,
                message,
                path,
                line,
                column,
                actualElement,
                actualAttribute,
                constraintName,
                expectedElements,
                schemaCodes);
    }
}
