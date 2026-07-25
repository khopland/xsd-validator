package io.github.khopland.xsd.validation;

import org.apache.xerces.xs.ElementPSVI;
import org.apache.xerces.xs.ItemPSVI;
import org.apache.xerces.xs.PSVIProvider;
import org.apache.xerces.xs.StringList;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

final class ValidationCoverageTracker extends DefaultHandler {
    private final PSVIProvider psviProvider;
    private final DocumentPathTracker pathTracker;
    private final DiagnosticCollector collector;
    private int depth;
    private boolean skippedOrLaxContent;

    ValidationCoverageTracker(
            PSVIProvider psviProvider,
            DocumentPathTracker pathTracker,
            DiagnosticCollector collector) {
        this.psviProvider = psviProvider;
        this.pathTracker = pathTracker;
        this.collector = collector;
    }

    @Override
    public void startElement(
            String uri,
            String localName,
            String qName,
            Attributes attributes) {
        ElementPSVI psvi = psviProvider.getElementPSVI();
        pathTracker.schemaType(psvi == null ? null : psvi.getTypeDefinition());
        depth++;
    }

    @Override
    public void endElement(String uri, String localName, String qName) {
        ElementPSVI psvi = psviProvider.getElementPSVI();
        if (!skippedOrLaxContent
                && depth > 1
                && psvi != null
                && psvi.getValidationAttempted() != ItemPSVI.VALIDATION_FULL
                && hasNoErrors(psvi.getErrorCodes())) {
            String path = pathTracker.context().path();
            skippedOrLaxContent = !collector.hasNonWarningDiagnosticAt(path);
        }
        depth--;
    }

    boolean skippedOrLaxContent() {
        return skippedOrLaxContent;
    }

    private static boolean hasNoErrors(StringList errors) {
        return errors == null || errors.getLength() == 0;
    }
}
