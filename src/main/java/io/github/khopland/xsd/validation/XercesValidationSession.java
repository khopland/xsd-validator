package io.github.khopland.xsd.validation;

import java.io.IOException;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.ValidatorHandler;
import org.apache.xerces.impl.XMLErrorReporter;
import org.apache.xerces.impl.xs.XSMessageFormatter;
import org.apache.xerces.jaxp.SAXParserFactoryImpl;
import org.apache.xerces.util.MessageFormatter;
import org.apache.xerces.xs.PSVIProvider;
import org.w3c.dom.ls.LSException;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

final class XercesValidationSession {
    private static final String ERROR_HANDLER =
            "http://apache.org/xml/properties/internal/error-handler";
    private static final String ERROR_REPORTER =
            "http://apache.org/xml/properties/internal/error-reporter";
    private static final int MAX_ISSUES = 100;

    private XercesValidationSession() {
    }

    static ValidationReport validate(
            XercesSchemaCompiler.CompiledSchema compiledSchema,
            Source source) {
        ValidatorHandler validator = compiledSchema.schema().newValidatorHandler();
        validator.setResourceResolver((type, namespaceUri, publicId, systemId, baseUri) -> {
            throw new LSException(
                    LSException.PARSE_ERR,
                    "External schema resolution is disabled during validation.");
        });
        DocumentPathTracker pathTracker = new DocumentPathTracker(validator);
        DiagnosticCollector collector = new DiagnosticCollector(pathTracker);
        ValidationCoverageTracker coverageTracker =
                new ValidationCoverageTracker((PSVIProvider) validator, pathTracker, collector);
        validator.setContentHandler(coverageTracker);
        boolean complete = true;

        try {
            configureStructuredErrors(validator, collector);
            XMLReader reader = xmlReader(source, collector);
            reader.setContentHandler(pathTracker);
            reader.parse(inputSource(source));
        } catch (SAXException
                | IOException
                | ParserConfigurationException
                | RuntimeException exception) {
            complete = false;
            if (!collector.hasFatal()) {
                collector.addFatal("xml-processing-stopped");
            }
        }

        List<ValidationIssue> allIssues = DiagnosticMapper.map(
                collector.diagnostics(),
                compiledSchema.identity(),
                compiledSchema.choiceIndex());
        boolean truncated = collector.truncated() || allIssues.size() > MAX_ISSUES;
        List<ValidationIssue> issues = allIssues.size() > MAX_ISSUES
                ? allIssues.subList(0, MAX_ISSUES)
                : allIssues;
        boolean valid = complete && !collector.hasErrors();
        return new ValidationReport(
                valid,
                collector.rawEventCount(),
                issues,
                compiledSchema.identity(),
                new ValidationCoverage(
                        complete,
                        truncated,
                        coverageTracker.skippedOrLaxContent()));
    }

    private static void configureStructuredErrors(
            ValidatorHandler validator,
            DiagnosticCollector collector)
            throws SAXException {
        XMLErrorReporter reporter = (XMLErrorReporter) validator.getProperty(ERROR_REPORTER);
        MessageFormatter formatter = reporter.getMessageFormatter(XSMessageFormatter.SCHEMA_DOMAIN);
        reporter.putMessageFormatter(
                XSMessageFormatter.SCHEMA_DOMAIN,
                new CapturingMessageFormatter(formatter, collector));
        validator.setProperty(ERROR_HANDLER, collector);
    }

    private static XMLReader newSecureReader(DiagnosticCollector collector)
            throws SAXException, ParserConfigurationException {
        SAXParserFactoryImpl factory = new SAXParserFactoryImpl();
        factory.setNamespaceAware(true);
        XMLReader reader = factory.newSAXParser().getXMLReader();
        configureSecureReader(reader, collector);
        return reader;
    }

    private static XMLReader xmlReader(
            Source source,
            DiagnosticCollector collector)
            throws SAXException, ParserConfigurationException {
        if (source instanceof SAXSource saxSource && saxSource.getXMLReader() != null) {
            XMLReader reader = saxSource.getXMLReader();
            configureSecureReader(reader, collector);
            return reader;
        }
        return newSecureReader(collector);
    }

    private static void configureSecureReader(
            XMLReader reader,
            DiagnosticCollector collector)
            throws SAXException {
        reader.setFeature("http://xml.org/sax/features/namespaces", true);
        reader.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
        reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        reader.setProperty(ERROR_HANDLER, collector);
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
}
