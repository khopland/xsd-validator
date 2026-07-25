package io.github.khopland.xsd.validation.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.xerces.xs.ElementPSVI;
import org.apache.xerces.xs.ItemPSVI;
import org.apache.xerces.xs.PSVIProvider;
import org.apache.xerces.xs.StringList;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

final class ValidationCoverageTracker extends DefaultHandler {
    private final PSVIProvider psviProvider;
    private final DocumentPathTracker pathTracker;
    private final Set<String> unassessedPaths = new LinkedHashSet<>();
    private int depth;

    ValidationCoverageTracker(
            PSVIProvider psviProvider,
            DocumentPathTracker pathTracker) {
        this.psviProvider = psviProvider;
        this.pathTracker = pathTracker;
    }

    @Override
    public void startElement(
            String uri,
            String localName,
            String qName,
            Attributes attributes) {
        depth++;
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        ElementPSVI psvi = psviProvider.getElementPSVI();
        if (depth > 1
                && psvi != null
                && psvi.getValidationAttempted() != ItemPSVI.VALIDATION_FULL
                && hasNoErrors(psvi.getErrorCodes())) {
            unassessedPaths.add(pathTracker.context().path());
        }
        depth--;
    }

    boolean skippedOrLaxContent(List<RawDiagnostic> diagnostics) {
        return unassessedPaths.stream().anyMatch(path ->
                diagnostics.stream().noneMatch(diagnostic ->
                        diagnostic.path().equals(path)
                                && diagnostic.severity()
                                        != io.github.khopland.xsd.validation.ValidationSeverity.WARNING));
    }

    private static boolean hasNoErrors(StringList errors) {
        return errors == null || errors.getLength() == 0;
    }
}
