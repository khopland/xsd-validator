package io.github.khopland.xsd.validation;

/**
 * Hard limits that bound materialized schema content during compilation.
 *
 * @param maxRootSchemaBytes maximum bytes retained for the root schema
 * @param maxDependencyCount maximum distinct resolved schema dependencies
 * @param maxDependencyBytes maximum bytes retained for one schema dependency
 * @param maxTotalDependencyBytes maximum bytes retained across all schema dependencies
 */
public record SchemaCompilationLimits(
        int maxRootSchemaBytes,
        int maxDependencyCount,
        int maxDependencyBytes,
        long maxTotalDependencyBytes) {

    /** Default limits used by {@link BetterXsdValidator#compile}. */
    public static final SchemaCompilationLimits DEFAULT = new SchemaCompilationLimits(
            16 * 1024 * 1024,
            64,
            16 * 1024 * 1024,
            64L * 1024 * 1024);

    /**
     * Effectively disables library-enforced schema size and dependency limits.
     *
     * <p>Only use this option when the root schema and every dependency resolver are
     * controlled by a trusted party. JVM memory and maximum array sizes still apply.
     */
    public static final SchemaCompilationLimits TRUSTED_UNBOUNDED =
            new SchemaCompilationLimits(
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    Long.MAX_VALUE);

    /** Validates that every limit can bound compilation. */
    public SchemaCompilationLimits {
        if (maxRootSchemaBytes <= 0) {
            throw new IllegalArgumentException("maxRootSchemaBytes must be positive.");
        }
        if (maxDependencyCount <= 0) {
            throw new IllegalArgumentException("maxDependencyCount must be positive.");
        }
        if (maxDependencyBytes <= 0) {
            throw new IllegalArgumentException("maxDependencyBytes must be positive.");
        }
        if (maxTotalDependencyBytes <= 0) {
            throw new IllegalArgumentException(
                    "maxTotalDependencyBytes must be positive.");
        }
    }
}
