package io.github.khopland.xsd.validation;

import java.util.List;
import java.util.Objects;

/**
 * Result of validating one XML document.
 *
 * @param valid         whether parsing completed and Xerces reported no errors
 * @param rawEventCount number of recoverable engine events before grouping
 * @param issues        grouped issues in document order
 * @param schema        identity of the compiled schema and its dependencies
 * @param coverage      limits on what validation assessed and retained
 */
public record ValidationReport(
        boolean valid,
        int rawEventCount,
        List<ValidationIssue> issues,
        SchemaIdentity schema,
        ValidationCoverage coverage) {

    /**
     * Copies the issue list to preserve immutability.
     */
    public ValidationReport {
        issues = List.copyOf(issues);
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(coverage, "coverage");
    }

    /**
     * Returns whether parsing reached the end of the document.
     *
     * @return the coverage completeness flag
     */
    public boolean complete() {
        return coverage.complete();
    }
}
