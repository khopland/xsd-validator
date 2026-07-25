package io.github.khopland.xsd.validation;

/**
 * Severity assigned by the XML or schema validation engine.
 */
public enum ValidationSeverity {
    /** A non-failing warning. */
    WARNING,
    /** A recoverable validation error. */
    ERROR,
    /** A fatal error after which the remainder was not assessed. */
    FATAL
}
