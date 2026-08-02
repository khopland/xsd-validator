package io.github.khopland.xsd.validation;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.ValidatorHandler;
import org.apache.xerces.jaxp.DocumentBuilderFactoryImpl;
import org.apache.xerces.jaxp.SAXParserFactoryImpl;
import org.apache.xerces.jaxp.validation.XMLSchemaFactory;
import org.apache.xerces.jaxp.validation.XSGrammarPoolContainer;
import org.apache.xerces.xni.grammars.Grammar;
import org.apache.xerces.xni.grammars.XMLGrammarDescription;
import org.apache.xerces.xni.grammars.XSGrammar;
import org.apache.xerces.xs.PSVIProvider;
import org.apache.xerces.xs.XSModel;
import org.jspecify.annotations.Nullable;

/**
 * Centralizes Xerces implementation types that sit behind the package-private
 * compatibility boundary.
 */
final class XercesCompatibility {
    private XercesCompatibility() {}

    static SchemaFactory schemaFactory() {
        return new XMLSchemaFactory();
    }

    static DocumentBuilderFactory documentBuilderFactory() {
        return new DocumentBuilderFactoryImpl();
    }

    static SAXParserFactory saxParserFactory() {
        return new SAXParserFactoryImpl();
    }

    static PSVIProvider psviProvider(ValidatorHandler validator) {
        if (validator instanceof PSVIProvider provider) {
            return provider;
        }
        throw new IllegalStateException(
                "The configured schema does not expose Xerces PSVI data.");
    }

    static @Nullable XSModel schemaModel(Schema schema) {
        if (!(schema instanceof XSGrammarPoolContainer container)) {
            throw new IllegalStateException(
                    "The configured schema does not expose a Xerces grammar pool.");
        }
        Grammar[] grammars = container
                .getGrammarPool()
                .retrieveInitialGrammarSet(XMLGrammarDescription.XML_SCHEMA);
        if (grammars.length == 0) {
            return null;
        }
        XSGrammar[] schemaGrammars = new XSGrammar[grammars.length];
        for (int index = 0; index < grammars.length; index++) {
            if (!(grammars[index] instanceof XSGrammar grammar)) {
                throw new IllegalStateException(
                        "The Xerces grammar pool returned a non-schema grammar.");
            }
            schemaGrammars[index] = grammar;
        }
        return schemaGrammars[0].toXSModel(schemaGrammars);
    }
}
