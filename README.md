# Better XSD Validation

A small Java 17 library that compiles XSD 1.0 with Xerces and returns safe,
structured validation reports. It keeps raw XML values out of issues while
adding indexed paths, QNames, expected elements, grouped Xerces keys, identity
constraint names, and honest coverage state.

## Installation

Better XSD Validation requires Java 17 or newer and is available from Maven
Central.

Maven:

```xml
<dependency>
  <groupId>io.github.khopland.xsd.validation</groupId>
  <artifactId>xsd-validator</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle:

```kotlin
implementation("io.github.khopland.xsd.validation:xsd-validator:0.1.0")
```

## Usage

```java
BetterXsdValidator validator =
        BetterXsdValidator.compile(new StreamSource(schemaFile));

ValidationReport report =
        validator.validate(new StreamSource(xmlFile));

for (ValidationIssue issue : report.issues()) {
    System.out.println(issue.path() + ": " + issue.message());
}
```

Compile once and reuse the validator, including across threads. Each call to
`validate` creates an isolated Xerces session.

Hard processing limits can be changed without recompiling the schema:

```java
validator = validator.withLimits(new ValidationLimits(512, 200));
```

Higher limits allow more structural state to be retained while processing one
document.

By default, schema imports, includes, and redefines are restricted to local
files. Set a system ID on the schema `Source` when it uses relative
dependencies. For classpath, JAR, in-memory, or deliberately approved remote
dependencies, call `compile(schemaSource, resolver)` with a standard
`LSResourceResolver`. The resolver is an explicit trust boundary; resolved
dependencies are fingerprinted and still cannot contain a DOCTYPE.

Schema compilation is also bounded by default: 16 MiB for the root schema, 64
dependencies, 16 MiB per dependency, and 64 MiB across dependencies. Trusted
schema sets that need higher limits can opt in explicitly:

```java
SchemaCompilationLimits limits =
        new SchemaCompilationLimits(
                32 * 1024 * 1024,
                128,
                32 * 1024 * 1024,
                128L * 1024 * 1024);

BetterXsdValidator validator =
        BetterXsdValidator.compile(schemaSource, resolver, limits);
```

## Report contract

- `valid` is true only when parsing completed and Xerces reported no errors.
- `complete` is false after a fatal XML or processing error.
- `rawEventCount` counts Xerces events before related events are grouped.
- `issues` are immutable, ordered, capped at 100, and contain no raw lexical
  XML values. Namespaced path segments use `{namespace}local[index]`.
  `actualElement`, `actualAttribute`, and `constraintName` are nullable.
  `SchemaIdentity` accepts a nullable target namespace and normalizes it to an
  empty string; the rest of the API is non-null by default through JSpecify.
- `schema` contains the target namespace and a dependency-aware SHA-256
  fingerprint.
- `coverage` reports incomplete parsing, truncation, and allowed wildcard
  content that was skipped or only laxly assessed.
- Default processing stops with `XML_PROCESSING_ERROR` beyond 256 nested
  elements or 100 distinct child QNames under one parent. Use
  `withLimits(ValidationLimits)` to change those bounds.

Use `ValidationIssue.code()` for application logic. Messages are safe prose,
not a parsing interface.

## Redacted diagnostic example

Given the submitted XML `<age>customer-secret</age>` and an `xs:int`
declaration, the report contains safe structural information:

```text
code: INVALID_VALUE
path: /age[1]
message: Element <age> does not satisfy type 'int'.
schemaCodes: [cvc-datatype-valid.1.2.1, cvc-type.3.1.3]
```

The submitted value does not appear in the issue, report `toString()`, or
grouped schema codes.

## Scope

This library validates XML against XSD 1.0. Business rules, code-list rules,
Schematron assertions, and requirements not expressed by the compiled XSD are
outside its validation result.

## Stable issue codes

| Family | Codes |
| --- | --- |
| XML and root | `MALFORMED_XML`, `XML_PROCESSING_ERROR`, `ROOT_NAMESPACE_MISMATCH`, `UNDECLARED_ROOT` |
| Elements and content | `UNEXPECTED_ELEMENT`, `MISSING_ELEMENT`, `DUPLICATE_ELEMENT`, `MIN_OCCURS_NOT_MET`, `MAX_OCCURS_EXCEEDED` |
| Choices | `CHOICE_ALREADY_SELECTED`, `CHOICE_BRANCH_INCOMPLETE` |
| Values and facets | `INVALID_VALUE`, `ENUMERATION_VIOLATION`, `PATTERN_MISMATCH`, `LENGTH_VIOLATION`, `MINIMUM_VIOLATION`, `MAXIMUM_VIOLATION`, `TOTAL_DIGITS_EXCEEDED`, `FRACTION_DIGITS_EXCEEDED` |
| Attributes | `REQUIRED_ATTRIBUTE_MISSING`, `ATTRIBUTE_NOT_ALLOWED`, `INVALID_ATTRIBUTE_VALUE`, `ATTRIBUTE_FIXED_VALUE_MISMATCH` |
| Identity constraints | `DUPLICATE_KEY`, `DUPLICATE_UNIQUE`, `KEY_REFERENCE_NOT_FOUND`, `KEY_VALUE_MISSING` |
| Substitution and instance hints | `ABSTRACT_ELEMENT_REQUIRES_SUBSTITUTE`, `INVALID_XSI_TYPE`, `XSI_TYPE_NOT_FOUND`, `XSI_TYPE_NOT_DERIVED`, `XSI_NIL_NOT_ALLOWED`, `NILLED_ELEMENT_HAS_CONTENT`, `XSI_NIL_FIXED_VALUE_CONFLICT` |
| Fallback | `SCHEMA_VALIDATION_ERROR` |

## License

Licensed under the [Apache License 2.0](LICENSE).
