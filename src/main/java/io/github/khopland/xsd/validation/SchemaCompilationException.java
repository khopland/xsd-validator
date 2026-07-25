package io.github.khopland.xsd.validation;

/**
 * Indicates that the supplied schema could not be compiled safely.
 */
public final class SchemaCompilationException extends Exception {
    /**
     * Creates an exception with a safe explanation.
     *
     * @param message explanation
     */
    public SchemaCompilationException(String message) {
        super(message);
    }

    /**
     * Creates an exception with its underlying cause.
     *
     * @param message safe explanation
     * @param cause   underlying compilation failure
     */
    public SchemaCompilationException(String message, Throwable cause) {
        super(message, cause);
    }
}
