package io.github.khopland.xsd.validation;

import io.github.khopland.xsd.validation.internal.XercesSchemaCompiler;
import io.github.khopland.xsd.validation.internal.XercesValidationSession;
import javax.xml.transform.Source;

/**
 * Compiles an XSD once and creates an isolated validation session per document.
 */
public final class BetterXsdValidator {
    private final XercesSchemaCompiler.CompiledSchema compiledSchema;

    private BetterXsdValidator(XercesSchemaCompiler.CompiledSchema compiledSchema) {
        this.compiledSchema = compiledSchema;
    }

    public static BetterXsdValidator compile(Source schemaSource)
            throws SchemaCompilationException {
        return new BetterXsdValidator(XercesSchemaCompiler.compile(schemaSource));
    }

    public ValidationReport validate(Source xmlSource) {
        return XercesValidationSession.validate(compiledSchema, xmlSource);
    }
}
