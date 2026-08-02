package io.github.khopland.xsd.validation;

import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.element;
import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.isSafeName;
import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.issue;
import static io.github.khopland.xsd.validation.DiagnosticMappingSupport.safeNameArgument;

import io.github.khopland.xsd.validation.ValidationObservation.RawDiagnostic;
import java.util.List;
import java.util.Optional;
import javax.xml.namespace.QName;
import org.jspecify.annotations.Nullable;

/** Maps attribute validation diagnostics while retaining only safe public names. */
final class AttributeDiagnosticMapper {
    private AttributeDiagnosticMapper() {
    }

    static @Nullable DiagnosticIssueBuilder map(
            RawDiagnostic diagnostic,
            @Nullable QName actualAttribute) {
        return switch (diagnostic.key()) {
            case "cvc-complex-type.4", "cvc-complex-type.4_ns" -> issue(
                    diagnostic,
                    "REQUIRED_ATTRIBUTE_MISSING",
                    "Required attribute " + attribute(actualAttribute)
                            + " is missing from " + element(diagnostic.actualElement()) + ".",
                    List.of(),
                    actualAttribute);
            case "cvc-complex-type.3.2.1", "cvc-complex-type.3.2.2" -> issue(
                    diagnostic,
                    "ATTRIBUTE_NOT_ALLOWED",
                    "Attribute " + attribute(actualAttribute)
                            + " is not allowed on " + element(diagnostic.actualElement()) + ".",
                    List.of(),
                    actualAttribute);
            case "cvc-attribute.3" -> {
                String typeName = safeNameArgument(diagnostic.arguments(), 3)
                        .orElse("the declared type");
                yield issue(
                        diagnostic,
                        "INVALID_ATTRIBUTE_VALUE",
                        "Attribute " + attribute(actualAttribute)
                                + " on " + element(diagnostic.actualElement())
                                + " does not satisfy type '" + typeName + "'.",
                        List.of(),
                        actualAttribute);
            }
            case "cvc-attribute.4", "cvc-complex-type.3.1" -> issue(
                    diagnostic,
                    "ATTRIBUTE_FIXED_VALUE_MISMATCH",
                    "Attribute " + attribute(actualAttribute)
                            + " on " + element(diagnostic.actualElement())
                            + " must use its schema-defined fixed value.",
                    List.of(),
                    actualAttribute);
            default -> null;
        };
    }

    static @Nullable QName attributeName(RawDiagnostic diagnostic) {
        Object lexicalArgument = diagnostic.arguments().length < 2
                ? null
                : diagnostic.arguments()[1];
        if (!isAttributeDiagnostic(diagnostic.key())
                || lexicalArgument == null) {
            return null;
        }
        String lexicalName = lexicalArgument.toString();
        int separator = lexicalName.indexOf(':');
        String localName = separator < 0
                ? lexicalName
                : lexicalName.substring(separator + 1);
        if (!isSafeName(localName)) {
            return null;
        }
        Optional<QName> present = diagnostic.attributes().stream()
                .filter(name -> name.getLocalPart().equals(localName))
                .findFirst();
        if (present.isPresent()) {
            return present.get();
        }
        Object namespaceArgument = diagnostic.arguments().length > 2
                ? diagnostic.arguments()[2]
                : null;
        if ("cvc-complex-type.4_ns".equals(diagnostic.key())
                && namespaceArgument != null) {
            return new QName(namespaceArgument.toString(), localName);
        }
        return new QName(localName);
    }

    private static boolean isAttributeDiagnostic(String key) {
        return key.startsWith("cvc-attribute.")
                || key.startsWith("cvc-complex-type.3.")
                || key.equals("cvc-complex-type.4")
                || key.equals("cvc-complex-type.4_ns");
    }

    private static String attribute(@Nullable QName name) {
        return name == null ? "the current attribute" : "@" + name.getLocalPart();
    }
}
