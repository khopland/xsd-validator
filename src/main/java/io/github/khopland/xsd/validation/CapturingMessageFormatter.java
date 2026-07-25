package io.github.khopland.xsd.validation;

import org.apache.xerces.util.MessageFormatter;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.MissingResourceException;

final class CapturingMessageFormatter implements MessageFormatter {
    private final MessageFormatter delegate;
    private final DiagnosticCollector collector;

    CapturingMessageFormatter(MessageFormatter delegate, DiagnosticCollector collector) {
        this.delegate = delegate;
        this.collector = collector;
    }

    @Override
    public String formatMessage(
            @Nullable Locale locale,
            String key,
            @Nullable Object @Nullable [] arguments)
            throws MissingResourceException {
        collector.captureArguments(key, arguments);
        return delegate.formatMessage(locale, key, arguments);
    }
}
