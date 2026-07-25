package io.github.khopland.xsd.validation;

import java.util.List;
import java.util.Objects;
import javax.xml.namespace.QName;

/**
 * A safe, structured validation diagnostic. Raw XML values are never retained.
 * The {@code code} is the stable, machine-readable contract; {@code message}
 * is intended for people and must not be parsed.
 *
 * @param severity issue severity
 * @param code stable library code documented in the project README
 * @param message safe human-readable explanation
 * @param path namespace-aware validation location rendered as an indexed element path
 * @param line one-based line, or a negative value when unavailable
 * @param column one-based column, or a negative value when unavailable
 * @param actualElement element at the validation location, when available
 * @param actualAttribute relevant attribute, when available
 * @param constraintName identity-constraint name, when relevant
 * @param expectedElements bounded valid-next element preview
 * @param schemaCodes grouped Xerces schema keys
 */
public record ValidationIssue(
        ValidationSeverity severity,
        String code,
        String message,
        String path,
        int line,
        int column,
        QName actualElement,
        QName actualAttribute,
        String constraintName,
        List<QName> expectedElements,
        List<String> schemaCodes) {

    /**
     * Copies collection fields to preserve immutability.
     */
    public ValidationIssue {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(path, "path");
        expectedElements = List.copyOf(expectedElements);
        schemaCodes = List.copyOf(schemaCodes);
    }
}
