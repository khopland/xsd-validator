package io.github.khopland.xsd.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.ValidatorHandler;
import org.apache.xerces.impl.XMLErrorReporter;
import org.apache.xerces.impl.xs.XSMessageFormatter;
import org.junit.jupiter.api.Test;

class XercesDiagnosticAdapterTest {
    private static final String ERROR_REPORTER =
            "http://apache.org/xml/properties/internal/error-reporter";

    @Test
    void installsWhenTheSchemaMessageFormatterIsMissing() throws Exception {
        XercesSchemaCompiler.CompiledSchema compiled = XercesSchemaCompiler.compile(
                new StreamSource(new StringReader("""
                        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
                          <xs:element name="value" type="xs:string"/>
                        </xs:schema>
                        """)),
                null);
        ValidatorHandler validator = compiled.schema().newValidatorHandler();
        XMLErrorReporter reporter =
                (XMLErrorReporter) validator.getProperty(ERROR_REPORTER);
        reporter.removeMessageFormatter(XSMessageFormatter.SCHEMA_DOMAIN);

        XercesDiagnosticAdapter adapter = XercesDiagnosticAdapter.install(
                validator,
                (domain, key, arguments, severity, line, column) -> {});

        assertThat(adapter).isNotNull();
        assertThat(reporter.getMessageFormatter(XSMessageFormatter.SCHEMA_DOMAIN))
                .isNull();
    }
}
