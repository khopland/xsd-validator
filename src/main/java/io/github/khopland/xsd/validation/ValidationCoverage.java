package io.github.khopland.xsd.validation;

/**
 * States which parts of the document were assessed by XSD validation.
 *
 * @param complete whether parsing reached the end of the document
 * @param issuesTruncated whether engine events or returned issues exceeded a safety cap
 * @param skippedOrLaxContent whether allowed wildcard content was not fully assessed
 */
public record ValidationCoverage(
        boolean complete,
        boolean issuesTruncated,
        boolean skippedOrLaxContent) {
}
