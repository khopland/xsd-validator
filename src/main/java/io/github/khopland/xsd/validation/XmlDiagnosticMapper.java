package io.github.khopland.xsd.validation;

import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.element;
import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.issue;

import io.github.khopland.xsd.validation.ValidationObservation.RawDiagnostic;
import javax.xml.namespace.QName;
import org.jspecify.annotations.Nullable;

/** Maps document-processing and root-element diagnostics. */
final class XmlDiagnosticMapper {
    private XmlDiagnosticMapper() {
    }

    static @Nullable DiagnosticIssueBuilder map(
            RawDiagnostic diagnostic,
            SchemaIdentity schema,
            ChoiceIndex choices) {
        if ("cvc-elt.1.a".equals(diagnostic.key())
                && diagnostic.actualElement() != null
                && choices.hasRootLocalName(diagnostic.actualElement().getLocalPart())
                && !namespace(diagnostic.actualElement()).equals(schema.targetNamespace())) {
            String actualNamespace = namespace(diagnostic.actualElement());
            String message = "Root element " + element(diagnostic.actualElement())
                    + " uses namespace '" + actualNamespace + "'; the schema expects '"
                    + schema.targetNamespace() + "'.";
            return issue(diagnostic, "ROOT_NAMESPACE_MISMATCH", message);
        }

        return switch (diagnostic.key()) {
            case "cvc-elt.1.a" -> issue(
                    diagnostic,
                    "UNDECLARED_ROOT",
                    "Root element " + element(diagnostic.actualElement())
                            + " is not declared by the compiled schema.");
            case "xml-processing-stopped" -> issue(
                    diagnostic,
                    "XML_PROCESSING_ERROR",
                    "XML validation could not process the supplied Source.");
            default -> null;
        };
    }

    private static String namespace(@Nullable QName name) {
        return name == null ? "" : name.getNamespaceURI();
    }
}
