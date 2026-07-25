# Better XSD Validation

A small Java 17 library that compiles XSD 1.0 with Xerces and returns safe,
structured validation reports. It keeps raw XML values out of issues while
adding indexed paths, QNames, expected elements, grouped Xerces keys, identity
constraint names, and honest coverage state.

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

Schema imports, includes, and redefines are restricted to local files. Set a
system ID on the schema `Source` when it uses relative dependencies. External
entities and network schema resolution are disabled.

## Report contract

- `valid` is true only when parsing completed and Xerces reported no errors.
- `complete` is false after a fatal XML or processing error.
- `rawEventCount` counts Xerces events before related events are grouped.
- `issues` are immutable, ordered, capped at 100, and contain no raw lexical
  XML values.
- `schema` contains the target namespace and a dependency-aware SHA-256
  fingerprint.
- `coverage` reports incomplete parsing, truncation, and allowed wildcard
  content that was skipped or only laxly assessed.

Use `ValidationIssue.code()` for application logic. Messages are safe prose,
not a parsing interface.

## Stable issue codes

| Family | Codes |
| --- | --- |
| XML and root | `MALFORMED_XML`, `ROOT_NAMESPACE_MISMATCH`, `UNDECLARED_ROOT` |
| Elements and content | `UNEXPECTED_ELEMENT`, `MISSING_ELEMENT`, `DUPLICATE_ELEMENT`, `MIN_OCCURS_NOT_MET`, `MAX_OCCURS_EXCEEDED` |
| Choices | `CHOICE_ALREADY_SELECTED`, `CHOICE_BRANCH_INCOMPLETE` |
| Values and facets | `INVALID_VALUE`, `ENUMERATION_VIOLATION`, `PATTERN_MISMATCH`, `LENGTH_VIOLATION`, `MINIMUM_VIOLATION`, `MAXIMUM_VIOLATION`, `TOTAL_DIGITS_EXCEEDED`, `FRACTION_DIGITS_EXCEEDED` |
| Attributes | `REQUIRED_ATTRIBUTE_MISSING`, `ATTRIBUTE_NOT_ALLOWED`, `INVALID_ATTRIBUTE_VALUE`, `ATTRIBUTE_FIXED_VALUE_MISMATCH` |
| Identity constraints | `DUPLICATE_KEY`, `DUPLICATE_UNIQUE`, `KEY_REFERENCE_NOT_FOUND`, `KEY_VALUE_MISSING` |
| Substitution and instance hints | `ABSTRACT_ELEMENT_REQUIRES_SUBSTITUTE`, `INVALID_XSI_TYPE`, `XSI_TYPE_NOT_FOUND`, `XSI_TYPE_NOT_DERIVED`, `XSI_NIL_NOT_ALLOWED`, `NILLED_ELEMENT_HAS_CONTENT`, `XSI_NIL_FIXED_VALUE_CONFLICT` |
| Fallback | `SCHEMA_VALIDATION_ERROR` |