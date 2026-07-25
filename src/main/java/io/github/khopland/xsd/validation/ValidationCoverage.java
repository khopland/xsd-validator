package io.github.khopland.xsd.validation;

/**
 * States which parts of the document were assessed by XSD validation.
 */
public record ValidationCoverage(
        boolean complete,
        boolean issuesTruncated,
        boolean skippedOrLaxContent) {
}
