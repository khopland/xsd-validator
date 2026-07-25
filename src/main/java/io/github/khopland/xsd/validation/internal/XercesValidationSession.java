package io.github.khopland.xsd.validation.internal;

import io.github.khopland.xsd.validation.ValidationCoverage;
import io.github.khopland.xsd.validation.ValidationIssue;
import io.github.khopland.xsd.validation.ValidationReport;
import io.github.khopland.xsd.validation.ValidationSeverity;
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
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

public final class XercesValidationSession {
    private static final String ERROR_HANDLER =
            "http://apache.org/xml/properties/internal/error-handler";
    private static final String ERROR_REPORTER =
            "http://apache.org/xml/properties/internal/error-reporter";
    private static final int MAX_ISSUES = 100;

    private XercesValidationSession() {
    }

    public static ValidationReport validate(
            XercesSchemaCompiler.CompiledSchema compiledSchema,
            Source source) {
        ValidatorHandler validator = compiledSchema.schema().newValidatorHandler();
        validator.setContentHandler(new DefaultHandler());
        DocumentPathTracker pathTracker = new DocumentPathTracker(validator);
        DiagnosticCollector collector = new DiagnosticCollector(pathTracker);
        boolean complete = true;

        try {
            configureStructuredErrors(validator, collector);
            XMLReader reader = newSecureReader(collector);
            reader.setContentHandler(pathTracker);
            reader.parse(inputSource(source));
        } catch (SAXException
                | IOException
                | ParserConfigurationException
                | RuntimeException exception) {
            complete = false;
            if (collector.diagnostics().stream()
                    .noneMatch(diagnostic -> diagnostic.severity() == ValidationSeverity.FATAL)) {
                collector.addFatal("xml-processing-stopped");
            }
        }

        List<ValidationIssue> allIssues = DiagnosticMapper.map(
                collector.diagnostics(),
                compiledSchema.identity(),
                compiledSchema.choiceIndex());
        boolean truncated = allIssues.size() > MAX_ISSUES;
        List<ValidationIssue> issues =
                truncated ? allIssues.subList(0, MAX_ISSUES) : allIssues;
        boolean valid = complete && allIssues.stream()
                .noneMatch(issue -> issue.severity() != ValidationSeverity.WARNING);
        return new ValidationReport(
                valid,
                collector.diagnostics().size(),
                issues,
                compiledSchema.identity(),
                new ValidationCoverage(complete, truncated, false));
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
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        XMLReader reader = factory.newSAXParser().getXMLReader();
        reader.setProperty(ERROR_HANDLER, collector);
        reader.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("External entity resolution is disabled.");
        });
        return reader;
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
        throw new SAXException("Milestone 0 supports StreamSource and SAXSource XML input.");
    }
}
