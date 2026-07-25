package io.github.khopland.xsd.validation;

import java.util.List;
import java.util.Objects;

/**
 * Result of validating one XML document.
 */
public record ValidationReport(
        boolean valid,
        int rawEventCount,
        List<ValidationIssue> issues,
        SchemaIdentity schema,
        ValidationCoverage coverage) {

    public ValidationReport {
        issues = List.copyOf(issues);
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(coverage, "coverage");
    }

    public boolean complete() {
        return coverage.complete();
    }
}
