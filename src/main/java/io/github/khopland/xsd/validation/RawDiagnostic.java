package io.github.khopland.xsd.validation;

import java.util.List;
import javax.xml.namespace.QName;
import org.apache.xerces.xs.XSTypeDefinition;
import org.jspecify.annotations.Nullable;

record RawDiagnostic(
        String domain,
        String key,
        @Nullable Object[] arguments,
        ValidationSeverity severity,
        String path,
        int line,
        int column,
        @Nullable QName actualElement,
        @Nullable QName parentElement,
        @Nullable XSTypeDefinition actualType,
        @Nullable XSTypeDefinition parentType,
        List<DocumentPathTracker.SeenElement> previousSiblings,
        List<DocumentPathTracker.SeenElement> children,
        List<QName> attributes) {

    RawDiagnostic {
        arguments = arguments.clone();
        previousSiblings = List.copyOf(previousSiblings);
        children = List.copyOf(children);
        attributes = List.copyOf(attributes);
    }
}
