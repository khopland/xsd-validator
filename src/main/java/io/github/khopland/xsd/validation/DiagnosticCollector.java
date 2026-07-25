package io.github.khopland.xsd.validation;

import java.util.ArrayList;
import java.util.List;
import org.apache.xerces.xni.XNIException;
import org.apache.xerces.xni.parser.XMLErrorHandler;
import org.apache.xerces.xni.parser.XMLParseException;

final class DiagnosticCollector implements XMLErrorHandler {
    private static final int MAX_RETAINED_DIAGNOSTICS = 1_000;

    private final DocumentPathTracker pathTracker;
    private final List<RawDiagnostic> diagnostics = new ArrayList<>();
    private int rawEventCount;
    private boolean truncated;
    private boolean hasErrors;
    private boolean hasFatal;
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

    boolean hasNonWarningDiagnosticAt(String path) {
        return diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.severity() != ValidationSeverity.WARNING
                        && diagnostic.path().equals(path));
    }

    int rawEventCount() {
        return rawEventCount;
    }

    boolean truncated() {
        return truncated;
    }

    boolean hasErrors() {
        return hasErrors;
    }

    boolean hasFatal() {
        return hasFatal;
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
        rawEventCount++;
        hasErrors |= severity != ValidationSeverity.WARNING;
        hasFatal |= severity == ValidationSeverity.FATAL;
        if (diagnostics.size() == MAX_RETAINED_DIAGNOSTICS) {
            truncated = true;
            pendingKey = null;
            pendingArguments = null;
            return;
        }
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
                context.actualType(),
                context.parentType(),
                context.previousSiblings(),
                context.children(),
                context.attributes()));
    }
}
