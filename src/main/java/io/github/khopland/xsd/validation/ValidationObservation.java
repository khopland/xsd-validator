package io.github.khopland.xsd.validation;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.ValidatorHandler;
import org.apache.xerces.jaxp.SAXParserFactoryImpl;
import org.apache.xerces.xs.ElementPSVI;
import org.apache.xerces.xs.ItemPSVI;
import org.apache.xerces.xs.PSVIProvider;
import org.apache.xerces.xs.StringList;
import org.apache.xerces.xs.XSTypeDefinition;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.ls.LSException;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Owns the structural and schema-derived evidence observed during one validation.
 */
final class ValidationObservation extends DefaultHandler {
    private static final int MAX_RETAINED_CHILDREN = 100;
    private static final int MAX_RETAINED_ATTRIBUTES = 100;
    private static final int MAX_RETAINED_DIAGNOSTICS = 1_000;
    private static final int MAX_ISSUES = 100;

    private final ValidatorHandler validator;
    private final PSVIProvider psviProvider;
    private final ValidationLimits limits;
    private final Deque<Frame> path = new ArrayDeque<>();
    private final Map<QName, Integer> rootCounts = new HashMap<>();
    private final List<RawDiagnostic> diagnostics = new ArrayList<>();
    private @Nullable Locator locator;
    private int depth;
    private int rawEventCount;
    private boolean skippedOrLaxContent;
    private boolean truncated;
    private boolean hasErrors;
    private boolean hasFatal;

    private ValidationObservation(
            ValidatorHandler validator,
            ValidationLimits limits) {
        this.validator = validator;
        this.psviProvider = (PSVIProvider) validator;
        this.limits = limits;
        validator.setContentHandler(new CoverageHandler());
    }

    static ValidationReport validate(
            XercesSchemaCompiler.CompiledSchema compiledSchema,
            Source source,
            ValidationLimits limits) {
        ValidatorHandler validator = compiledSchema.schema().newValidatorHandler();
        validator.setResourceResolver((type, namespaceUri, publicId, systemId, baseUri) -> {
            throw new LSException(
                    LSException.PARSE_ERR,
                    "External schema resolution is disabled during validation.");
        });
        ValidationObservation observation =
                new ValidationObservation(validator, limits);
        boolean complete = true;

        try {
            XercesDiagnosticAdapter diagnosticAdapter =
                    XercesDiagnosticAdapter.install(validator, observation::add);
            XMLReader reader = xmlReader(source, observation, diagnosticAdapter);
            reader.setContentHandler(observation);
            reader.parse(inputSource(source));
        } catch (SAXException
                | IOException
                | ParserConfigurationException
                | RuntimeException exception) {
            complete = false;
            observation.processingStopped();
        }

        return observation.report(complete, compiledSchema);
    }

    private void processingStopped() {
        if (!hasFatal) {
            add(
                    "",
                    "xml-processing-stopped",
                    new Object[0],
                    ValidationSeverity.FATAL,
                    line(),
                    column());
        }
    }

    private ValidationReport report(
            boolean complete,
            XercesSchemaCompiler.CompiledSchema compiledSchema) {
        List<ValidationIssue> allIssues = DiagnosticMapper.map(
                diagnostics,
                compiledSchema.identity(),
                compiledSchema.choiceIndex());
        boolean issuesTruncated = truncated || allIssues.size() > MAX_ISSUES;
        List<ValidationIssue> issues = allIssues.size() > MAX_ISSUES
                ? allIssues.subList(0, MAX_ISSUES)
                : allIssues;
        return new ValidationReport(
                complete && !hasErrors,
                rawEventCount,
                issues,
                compiledSchema.identity(),
                new ValidationCoverage(
                        complete,
                        issuesTruncated,
                        skippedOrLaxContent));
    }

    private static XMLReader newSecureReader(
            ValidationObservation observation,
            XercesDiagnosticAdapter diagnosticAdapter)
            throws SAXException, ParserConfigurationException {
        SAXParserFactoryImpl factory = new SAXParserFactoryImpl();
        factory.setNamespaceAware(true);
        XMLReader reader = factory.newSAXParser().getXMLReader();
        configureSecureReader(reader, observation, diagnosticAdapter);
        return reader;
    }

    /**
     * Returns a secured reader. A reader supplied by a SAXSource must be Xerces-backed and
     * is configured in place.
     */
    private static XMLReader xmlReader(
            Source source,
            ValidationObservation observation,
            XercesDiagnosticAdapter diagnosticAdapter)
            throws SAXException, ParserConfigurationException {
        if (source instanceof SAXSource saxSource && saxSource.getXMLReader() != null) {
            XMLReader reader = saxSource.getXMLReader();
            configureSecureReader(reader, observation, diagnosticAdapter);
            return reader;
        }
        return newSecureReader(observation, diagnosticAdapter);
    }

    /**
     * Mutates a Xerces-backed reader with the required SAX and Xerces security settings,
     * validation error handler, and entity resolver.
     */
    private static void configureSecureReader(
            XMLReader reader,
            ValidationObservation observation,
            XercesDiagnosticAdapter diagnosticAdapter)
            throws SAXException {
        reader.setFeature("http://xml.org/sax/features/namespaces", true);
        reader.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        reader.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd",
                false);
        diagnosticAdapter.installOn(reader);
        reader.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("External entity resolution is disabled.");
        });
    }

    private static InputSource inputSource(Source source) throws SAXException {
        if (source instanceof SAXSource saxSource && saxSource.getInputSource() != null) {
            return saxSource.getInputSource();
        }
        if (source instanceof StreamSource streamSource) {
            InputSource input = new InputSource();
            input.setByteStream(streamSource.getInputStream());
            input.setCharacterStream(streamSource.getReader());
            input.setPublicId(streamSource.getPublicId());
            input.setSystemId(streamSource.getSystemId());
            if (input.getByteStream() == null
                    && input.getCharacterStream() == null
                    && input.getSystemId() == null) {
                throw new SAXException("The XML Source has no content or system ID.");
            }
            return input;
        }
        throw new SAXException("Only StreamSource and SAXSource XML input are supported.");
    }

    @Override
    public void setDocumentLocator(Locator locator) {
        this.locator = locator;
        validator.setDocumentLocator(locator);
    }

    @Override
    public void startDocument() throws SAXException {
        path.clear();
        rootCounts.clear();
        validator.startDocument();
    }

    @Override
    public void endDocument() throws SAXException {
        validator.endDocument();
    }

    @Override
    public void startPrefixMapping(String prefix, String uri) throws SAXException {
        validator.startPrefixMapping(prefix, uri);
    }

    @Override
    public void endPrefixMapping(String prefix) throws SAXException {
        validator.endPrefixMapping(prefix);
    }

    @Override
    public void startElement(
            String uri,
            String localName,
            String qName,
            Attributes attributes)
            throws SAXException {
        if (path.size() == limits.maxElementDepth()) {
            throw new SAXException("XML nesting depth exceeds the validation limit.");
        }
        QName name = new QName(uri == null ? "" : uri, localName(localName, qName));
        Map<QName, Integer> counts =
                path.isEmpty() ? rootCounts : path.peekLast().childCounts;
        if (!counts.containsKey(name)
                && counts.size() == limits.maxDistinctChildNamesPerElement()) {
            throw new SAXException("Distinct child names exceed the validation limit.");
        }
        int index = counts.merge(name, 1, Integer::sum);
        path.addLast(new Frame(name, index, line(), attributeNames(attributes)));
        validator.startElement(uri, localName, qName, attributes);
    }

    @Override
    public void endElement(String uri, String localName, String qName)
            throws SAXException {
        validator.endElement(uri, localName, qName);
        Frame completed = path.removeLast();
        if (!path.isEmpty()) {
            path.peekLast().remember(new SeenElement(completed.name, completed.line));
        }
    }

    @Override
    public void characters(char[] characters, int start, int length)
            throws SAXException {
        validator.characters(characters, start, length);
    }

    @Override
    public void ignorableWhitespace(char[] characters, int start, int length)
            throws SAXException {
        validator.ignorableWhitespace(characters, start, length);
    }

    @Override
    public void processingInstruction(String target, String data)
            throws SAXException {
        validator.processingInstruction(target, data);
    }

    @Override
    public void skippedEntity(String name) throws SAXException {
        validator.skippedEntity(name);
    }

    private void add(
            String domain,
            String key,
            @Nullable Object[] arguments,
            ValidationSeverity severity,
            int diagnosticLine,
            int diagnosticColumn) {
        rawEventCount++;
        hasErrors |= severity != ValidationSeverity.WARNING;
        hasFatal |= severity == ValidationSeverity.FATAL;
        if (diagnostics.size() == MAX_RETAINED_DIAGNOSTICS) {
            truncated = true;
            return;
        }
        Context context = context();
        diagnostics.add(new RawDiagnostic(
                domain,
                key,
                arguments,
                severity,
                context.path(),
                diagnosticLine,
                diagnosticColumn,
                context.actualElement(),
                context.parentElement(),
                context.actualType(),
                context.parentType(),
                context.previousSiblings(),
                context.children(),
                context.attributes()));
    }

    private boolean hasNonWarningDiagnosticAt(String path) {
        return diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.severity() != ValidationSeverity.WARNING
                        && diagnostic.path().equals(path));
    }

    private Context context() {
        if (path.isEmpty()) {
            return new Context(
                    "/",
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    line(),
                    column());
        }

        List<Frame> frames = List.copyOf(path);
        Frame current = frames.get(frames.size() - 1);
        @Nullable Frame parent =
                frames.size() > 1 ? frames.get(frames.size() - 2) : null;
        StringBuilder renderedPath = new StringBuilder();
        for (Frame frame : frames) {
            renderedPath.append('/')
                    .append(frame.name)
                    .append('[')
                    .append(frame.index)
                    .append(']');
        }
        return new Context(
                renderedPath.toString(),
                current.name,
                parent == null ? null : parent.name,
                current.schemaType,
                parent == null ? null : parent.schemaType,
                parent == null ? List.of() : List.copyOf(parent.children),
                List.copyOf(current.children),
                current.attributes,
                line(),
                column());
    }

    private int line() {
        return locator == null ? -1 : locator.getLineNumber();
    }

    private int column() {
        return locator == null ? -1 : locator.getColumnNumber();
    }

    private static String localName(@Nullable String localName, String qName) {
        if (localName != null && !localName.isEmpty()) {
            return localName;
        }
        int separator = qName.indexOf(':');
        return separator < 0 ? qName : qName.substring(separator + 1);
    }

    private static List<QName> attributeNames(Attributes attributes) {
        List<QName> names = new ArrayList<>();
        for (int index = 0;
                index < attributes.getLength() && names.size() < MAX_RETAINED_ATTRIBUTES;
                index++) {
            String qName = attributes.getQName(index);
            int separator = qName.indexOf(':');
            String prefix = separator < 0 ? "" : qName.substring(0, separator);
            names.add(new QName(
                    attributes.getURI(index),
                    localName(attributes.getLocalName(index), qName),
                    prefix));
        }
        return List.copyOf(names);
    }

    private static boolean hasNoErrors(@Nullable StringList errors) {
        return errors == null || errors.getLength() == 0;
    }

    private final class CoverageHandler extends DefaultHandler {
        @Override
        public void startElement(
                String uri,
                String localName,
                String qName,
                Attributes attributes) {
            @Nullable ElementPSVI psvi = psviProvider.getElementPSVI();
            if (!path.isEmpty()) {
                path.peekLast().schemaType =
                        psvi == null ? null : psvi.getTypeDefinition();
            }
            depth++;
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            @Nullable ElementPSVI psvi = psviProvider.getElementPSVI();
            if (!skippedOrLaxContent
                    && depth > 1
                    && psvi != null
                    && psvi.getValidationAttempted() != ItemPSVI.VALIDATION_FULL
                    && hasNoErrors(psvi.getErrorCodes())) {
                String currentPath = context().path();
                skippedOrLaxContent = !hasNonWarningDiagnosticAt(currentPath);
            }
            depth--;
        }
    }

    private static final class Frame {
        private final QName name;
        private final int index;
        private final int line;
        private final List<QName> attributes;
        private @Nullable XSTypeDefinition schemaType;
        private final Map<QName, Integer> childCounts = new HashMap<>();
        private final List<SeenElement> children = new ArrayList<>();

        private Frame(QName name, int index, int line, List<QName> attributes) {
            this.name = name;
            this.index = index;
            this.line = line;
            this.attributes = attributes;
        }

        private void remember(SeenElement child) {
            if (children.size() == MAX_RETAINED_CHILDREN) {
                children.remove(0);
            }
            children.add(child);
        }
    }

    record SeenElement(QName name, int line) {
    }

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
            List<SeenElement> previousSiblings,
            List<SeenElement> children,
            List<QName> attributes) {

        RawDiagnostic {
            arguments = arguments.clone();
            previousSiblings = List.copyOf(previousSiblings);
            children = List.copyOf(children);
            attributes = List.copyOf(attributes);
        }
    }

    private record Context(
            String path,
            @Nullable QName actualElement,
            @Nullable QName parentElement,
            @Nullable XSTypeDefinition actualType,
            @Nullable XSTypeDefinition parentType,
            List<SeenElement> previousSiblings,
            List<SeenElement> children,
            List<QName> attributes,
            int line,
            int column) {
    }
}
