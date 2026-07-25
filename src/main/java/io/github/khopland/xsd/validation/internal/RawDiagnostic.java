package io.github.khopland.xsd.validation.internal;

import io.github.khopland.xsd.validation.ValidationSeverity;
import java.util.List;
import javax.xml.namespace.QName;

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
        List<DocumentPathTracker.SeenElement> previousSiblings) {

    RawDiagnostic {
        arguments = arguments == null ? new Object[0] : arguments.clone();
        previousSiblings = List.copyOf(previousSiblings);
    }
}
