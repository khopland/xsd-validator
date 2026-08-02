package io.github.khopland.xsd.validation;

import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.element;
import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.issue;

import io.github.khopland.xsd.validation.ValidationObservation.RawDiagnostic;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import org.jspecify.annotations.Nullable;

/** Maps substitution and schema-instance diagnostics to stable library issues. */
final class InstanceDiagnosticMapper {
    private static final QName XSI_TYPE = new QName(
            XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI,
            "type",
            "xsi");
    private static final QName XSI_NIL = new QName(
            XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI,
            "nil",
            "xsi");

    private InstanceDiagnosticMapper() {
    }

    static @Nullable DiagnosticIssueBuilder map(RawDiagnostic diagnostic) {
        return switch (diagnostic.key()) {
            case "cvc-elt.2" -> issue(
                    diagnostic,
                    "ABSTRACT_ELEMENT_REQUIRES_SUBSTITUTE",
                    "Abstract element " + element(diagnostic.actualElement())
                            + " must be replaced by a permitted substitution-group member.");
            case "cvc-elt.4.1" -> issue(
                    diagnostic,
                    "INVALID_XSI_TYPE",
                    "Attribute @xsi:type on " + element(diagnostic.actualElement())
                            + " must contain a valid QName.",
                    List.of(),
                    XSI_TYPE);
            case "cvc-elt.4.2" -> issue(
                    diagnostic,
                    "XSI_TYPE_NOT_FOUND",
                    "The schema cannot resolve @xsi:type on "
                            + element(diagnostic.actualElement()) + ".",
                    List.of(),
                    XSI_TYPE);
            case "cvc-elt.4.3" -> issue(
                    diagnostic,
                    "XSI_TYPE_NOT_DERIVED",
                    "The type selected by @xsi:type is not permitted for "
                            + element(diagnostic.actualElement()) + ".",
                    List.of(),
                    XSI_TYPE);
            case "cvc-elt.3.1" -> issue(
                    diagnostic,
                    "XSI_NIL_NOT_ALLOWED",
                    "Element " + element(diagnostic.actualElement())
                            + " is not nillable, so @xsi:nil is not allowed.",
                    List.of(),
                    XSI_NIL);
            case "cvc-elt.3.2.1" -> issue(
                    diagnostic,
                    "NILLED_ELEMENT_HAS_CONTENT",
                    "Element " + element(diagnostic.actualElement())
                            + " cannot contain content when @xsi:nil is true.",
                    List.of(),
                    XSI_NIL);
            case "cvc-elt.3.2.2" -> issue(
                    diagnostic,
                    "XSI_NIL_FIXED_VALUE_CONFLICT",
                    "Element " + element(diagnostic.actualElement())
                            + " has a fixed value and cannot use @xsi:nil.",
                    List.of(),
                    XSI_NIL);
            default -> null;
        };
    }
}
