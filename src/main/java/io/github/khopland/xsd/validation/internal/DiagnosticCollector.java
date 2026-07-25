package io.github.khopland.xsd.validation.internal;

import io.github.khopland.xsd.validation.ValidationSeverity;
import java.util.ArrayList;
import java.util.List;
import org.apache.xerces.xni.XNIException;
import org.apache.xerces.xni.parser.XMLErrorHandler;
import org.apache.xerces.xni.parser.XMLParseException;

final class DiagnosticCollector implements XMLErrorHandler {
    private final DocumentPathTracker pathTracker;
    private final List<RawDiagnostic> diagnostics = new ArrayList<>();
    private String pendingKey;
    private Object[] pendingArguments;

    DiagnosticCollector(DocumentPathTracker pathTracker) {
        this.pathTracker = pathTracker;
    }

    void captureArguments(String key, Object[] arguments) {
        pendingKey = key;
        pendingArguments = arguments == null ? new Object[0] : arguments.clone();
    }

    List<RawDiagnostic> diagnostics() {
        return List.copyOf(diagnostics);
    }

    void addFatal(String key) {
        add("", key, null, ValidationSeverity.FATAL);
    }

    @Override
    public void warning(String domain, String key, XMLParseException exception)
            throws XNIException {
        add(domain, key, exception, ValidationSeverity.WARNING);
    }

    @Override
    public void error(String domain, String key, XMLParseException exception)
            throws XNIException {
        add(domain, key, exception, ValidationSeverity.ERROR);
    }

    @Override
    public void fatalError(String domain, String key, XMLParseException exception)
            throws XNIException {
        add(domain, key, exception, ValidationSeverity.FATAL);
    }

    private void add(
            String domain,
            String key,
            XMLParseException exception,
            ValidationSeverity severity) {
        DocumentPathTracker.Context context = pathTracker.context();
        Object[] arguments = key.equals(pendingKey) ? pendingArguments : null;
        pendingKey = null;
        pendingArguments = null;
        diagnostics.add(new RawDiagnostic(
                domain,
                key,
                arguments,
                severity,
                context.path(),
                exception == null ? context.line() : exception.getLineNumber(),
                exception == null ? context.column() : exception.getColumnNumber(),
                context.actualElement(),
                context.parentElement(),
                context.previousSiblings()));
    }
}
