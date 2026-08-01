package io.github.khopland.xsd.validation;

import java.util.Locale;
import java.util.MissingResourceException;
import org.apache.xerces.impl.XMLErrorReporter;
import org.apache.xerces.impl.xs.XSMessageFormatter;
import org.apache.xerces.util.MessageFormatter;
import org.apache.xerces.xni.XNIException;
import org.apache.xerces.xni.parser.XMLErrorHandler;
import org.apache.xerces.xni.parser.XMLParseException;
import org.jspecify.annotations.Nullable;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

import javax.xml.validation.ValidatorHandler;

/**
 * Contains the Xerces-internal interception needed to retain structured schema keys and
 * arguments instead of parsing localized diagnostic prose.
 */
final class XercesDiagnosticAdapter implements XMLErrorHandler {
    private static final String ERROR_HANDLER =
            "http://apache.org/xml/properties/internal/error-handler";
    private static final String ERROR_REPORTER =
            "http://apache.org/xml/properties/internal/error-reporter";

    private final DiagnosticConsumer consumer;
    private @Nullable String pendingKey;
    private @Nullable Object @Nullable [] pendingArguments;

    private XercesDiagnosticAdapter(DiagnosticConsumer consumer) {
        this.consumer = consumer;
    }

    static XercesDiagnosticAdapter install(
            ValidatorHandler validator,
            DiagnosticConsumer consumer)
            throws SAXException {
        XercesDiagnosticAdapter adapter = new XercesDiagnosticAdapter(consumer);
        XMLErrorReporter reporter =
                (XMLErrorReporter) validator.getProperty(ERROR_REPORTER);
        MessageFormatter formatter =
                reporter.getMessageFormatter(XSMessageFormatter.SCHEMA_DOMAIN);
        reporter.putMessageFormatter(
                XSMessageFormatter.SCHEMA_DOMAIN,
                new CapturingFormatter(formatter, adapter));
        validator.setProperty(ERROR_HANDLER, adapter);
        return adapter;
    }

    void installOn(XMLReader reader) throws SAXException {
        reader.setProperty(ERROR_HANDLER, this);
    }

    @Override
    public void warning(String domain, String key, XMLParseException exception)
            throws XNIException {
        emit(domain, key, exception, ValidationSeverity.WARNING);
    }

    @Override
    public void error(String domain, String key, XMLParseException exception)
            throws XNIException {
        emit(domain, key, exception, ValidationSeverity.ERROR);
    }

    @Override
    public void fatalError(String domain, String key, XMLParseException exception)
            throws XNIException {
        emit(domain, key, exception, ValidationSeverity.FATAL);
    }

    private void captureArguments(
            String key,
            @Nullable Object @Nullable [] arguments) {
        pendingKey = key;
        pendingArguments = arguments == null ? new Object[0] : arguments.clone();
    }

    private void emit(
            String domain,
            String key,
            XMLParseException exception,
            ValidationSeverity severity) {
        @Nullable Object[] arguments;
        if (key.equals(pendingKey) && pendingArguments != null) {
            arguments = pendingArguments;
        } else {
            arguments = new Object[0];
        }
        pendingKey = null;
        pendingArguments = null;
        consumer.accept(
                domain,
                key,
                arguments,
                severity,
                exception.getLineNumber(),
                exception.getColumnNumber());
    }

    @FunctionalInterface
    interface DiagnosticConsumer {
        void accept(
                String domain,
                String key,
                @Nullable Object[] arguments,
                ValidationSeverity severity,
                int line,
                int column);
    }

    private static final class CapturingFormatter implements MessageFormatter {
        private final MessageFormatter delegate;
        private final XercesDiagnosticAdapter adapter;

        private CapturingFormatter(
                MessageFormatter delegate,
                XercesDiagnosticAdapter adapter) {
            this.delegate = delegate;
            this.adapter = adapter;
        }

        @Override
        public String formatMessage(
                @Nullable Locale locale,
                String key,
                @Nullable Object @Nullable [] arguments)
                throws MissingResourceException {
            adapter.captureArguments(key, arguments);
            return delegate.formatMessage(locale, key, arguments);
        }
    }
}
