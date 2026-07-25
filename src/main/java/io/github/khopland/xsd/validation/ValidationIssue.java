package io.github.khopland.xsd.validation;

import java.util.List;
import java.util.Objects;
import javax.xml.namespace.QName;

/**
 * A safe, structured validation diagnostic. Raw XML values are never retained.
 */
public record ValidationIssue(
        ValidationSeverity severity,
        String code,
        String message,
        String path,
        int line,
        int column,
        QName actualElement,
        List<QName> expectedElements,
        List<String> schemaCodes) {

    public ValidationIssue {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(path, "path");
        expectedElements = List.copyOf(expectedElements);
        schemaCodes = List.copyOf(schemaCodes);
    }
}
