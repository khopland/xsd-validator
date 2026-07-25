package io.github.khopland.xsd.validation;

import java.util.Objects;
import javax.xml.transform.Source;
import org.w3c.dom.ls.LSResourceResolver;

/**
 * Compiles an XSD once and creates an isolated validation session per document.
 */
public final class BetterXsdValidator {
    private final XercesSchemaCompiler.CompiledSchema compiledSchema;

    private BetterXsdValidator(XercesSchemaCompiler.CompiledSchema compiledSchema) {
        this.compiledSchema = compiledSchema;
    }

    /**
     * Compiles an XSD 1.0 schema for reuse.
     *
     * @param schemaSource schema content; a system ID is required for relative dependencies
     * @return a reusable, thread-safe validator
     * @throws SchemaCompilationException when the schema cannot be compiled safely
     */
    public static BetterXsdValidator compile(Source schemaSource)
            throws SchemaCompilationException {
        return new BetterXsdValidator(XercesSchemaCompiler.compile(schemaSource, null));
    }

    /**
     * Compiles an XSD 1.0 schema using an explicit dependency resolver.
     *
     * @param schemaSource root schema content
     * @param resolver resolver for classpath, JAR, in-memory, or other approved dependencies
     * @return a reusable, thread-safe validator
     * @throws SchemaCompilationException when the schema cannot be compiled safely
     */
    public static BetterXsdValidator compile(
            Source schemaSource,
            LSResourceResolver resolver)
            throws SchemaCompilationException {
        return new BetterXsdValidator(XercesSchemaCompiler.compile(
                schemaSource,
                Objects.requireNonNull(resolver, "resolver")));
    }

    /**
     * Validates one XML document in an isolated session.
     *
     * @param xmlSource XML content
     * @return the immutable validation report
     */
    public ValidationReport validate(Source xmlSource) {
        return XercesValidationSession.validate(compiledSchema, xmlSource);
    }
}
