package io.github.khopland.xsd.validation;

import org.w3c.dom.ls.LSResourceResolver;

import javax.xml.transform.Source;
import java.util.Objects;

/**
 * Compiles an XSD once and creates an isolated validation session per document.
 */
public final class BetterXsdValidator {
    private final XercesSchemaCompiler.CompiledSchema compiledSchema;
    private final ValidationLimits limits;

    private BetterXsdValidator(
            XercesSchemaCompiler.CompiledSchema compiledSchema,
            ValidationLimits limits) {
        this.compiledSchema = compiledSchema;
        this.limits = limits;
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
        return new BetterXsdValidator(
                XercesSchemaCompiler.compile(schemaSource, null),
                ValidationLimits.DEFAULT);
    }

    /**
     * Compiles an XSD 1.0 schema using an explicit dependency resolver.
     *
     * @param schemaSource root schema content
     * @param resolver     resolver for classpath, JAR, in-memory, or other approved dependencies
     * @return a reusable, thread-safe validator
     * @throws SchemaCompilationException when the schema cannot be compiled safely
     */
    public static BetterXsdValidator compile(
            Source schemaSource,
            LSResourceResolver resolver)
            throws SchemaCompilationException {
        return new BetterXsdValidator(
                XercesSchemaCompiler.compile(
                        schemaSource,
                        Objects.requireNonNull(resolver, "resolver")),
                ValidationLimits.DEFAULT);
    }

    /**
     * Returns a validator with the same compiled schema and different processing limits.
     *
     * @param limits hard limits applied to each validation
     * @return a reusable, thread-safe validator sharing this compiled schema
     */
    public BetterXsdValidator withLimits(ValidationLimits limits) {
        return new BetterXsdValidator(
                compiledSchema,
                Objects.requireNonNull(limits, "limits"));
    }

    /**
     * Validates one XML document in an isolated session.
     *
     * <p>{@link javax.xml.transform.stream.StreamSource StreamSource} and
     * {@link javax.xml.transform.sax.SAXSource SAXSource} are supported. A SAXSource that
     * supplies an {@link org.xml.sax.XMLReader XMLReader} must use a Xerces-backed reader.
     * The supplied reader is configured in place with the required SAX and Xerces-specific
     * security settings, validation error handler, and entity resolver.
     *
     * @param xmlSource XML content
     * @return the immutable validation report
     */
    public ValidationReport validate(Source xmlSource) {
        return ValidationObservation.validate(compiledSchema, xmlSource, limits);
    }
}
