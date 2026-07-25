package io.github.khopland.xsd.validation;

/**
 * Indicates that the supplied schema could not be compiled safely.
 */
public final class SchemaCompilationException extends Exception {
    public SchemaCompilationException(String message) {
        super(message);
    }

    public SchemaCompilationException(String message, Throwable cause) {
        super(message, cause);
    }
}
