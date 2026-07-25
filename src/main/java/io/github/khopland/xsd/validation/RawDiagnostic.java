package io.github.khopland.xsd.validation;

import java.util.List;
import javax.xml.namespace.QName;
import org.apache.xerces.xs.XSTypeDefinition;

record RawDiagnostic(
        String domain,
        String key,
        Object[] arguments,
        ValidationSeverity severity,
        String path,
        int line,
        int column,
        QName actualElement,
        QName parentElement,
        XSTypeDefinition actualType,
        XSTypeDefinition parentType,
        List<DocumentPathTracker.SeenElement> previousSiblings,
        List<DocumentPathTracker.SeenElement> children,
        List<QName> attributes) {

    RawDiagnostic {
        arguments = arguments == null ? new Object[0] : arguments.clone();
        previousSiblings = List.copyOf(previousSiblings);
        children = List.copyOf(children);
        attributes = List.copyOf(attributes);
    }
}
