package io.github.khopland.xsd.validation;

import java.util.Locale;
import java.util.MissingResourceException;
import org.apache.xerces.util.MessageFormatter;

final class CapturingMessageFormatter implements MessageFormatter {
    private final MessageFormatter delegate;
    private final DiagnosticCollector collector;

    CapturingMessageFormatter(MessageFormatter delegate, DiagnosticCollector collector) {
        this.delegate = delegate;
        this.collector = collector;
    }

    @Override
    public String formatMessage(Locale locale, String key, Object[] arguments)
            throws MissingResourceException {
        collector.captureArguments(key, arguments);
        return delegate.formatMessage(locale, key, arguments);
    }
}
