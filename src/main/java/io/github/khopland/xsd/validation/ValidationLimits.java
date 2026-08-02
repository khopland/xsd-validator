package io.github.khopland.xsd.validation;

/**
 * Hard limits that bound structural state while validating one XML document.
 *
 * @param maxElementDepth maximum number of simultaneously open elements
 * @param maxDistinctChildNamesPerElement maximum distinct child QNames tracked
 *     under one element
 * @param maxTotalElements maximum elements processed in one document
 * @param maxTotalCharacters maximum UTF-16 code units processed from character
 *     data and attribute values in one document
 */
public record ValidationLimits(
        int maxElementDepth,
        int maxDistinctChildNamesPerElement,
        long maxTotalElements,
        long maxTotalCharacters) {

    /** Default limits used by a newly compiled validator. */
    public static final ValidationLimits DEFAULT = new ValidationLimits(
            256,
            100,
            1_000_000,
            64L * 1024 * 1024);

    /**
     * Creates limits with the default total-element and total-character budgets.
     *
     * @param maxElementDepth maximum number of simultaneously open elements
     * @param maxDistinctChildNamesPerElement maximum distinct child QNames tracked
     *     under one element
     */
    public ValidationLimits(
            int maxElementDepth,
            int maxDistinctChildNamesPerElement) {
        this(
                maxElementDepth,
                maxDistinctChildNamesPerElement,
                DEFAULT.maxTotalElements,
                DEFAULT.maxTotalCharacters);
    }

    /** Validates that every limit can bound processing. */
    public ValidationLimits {
        if (maxElementDepth <= 0) {
            throw new IllegalArgumentException("maxElementDepth must be positive.");
        }
        if (maxDistinctChildNamesPerElement <= 0) {
            throw new IllegalArgumentException(
                    "maxDistinctChildNamesPerElement must be positive.");
        }
        if (maxTotalElements <= 0) {
            throw new IllegalArgumentException("maxTotalElements must be positive.");
        }
        if (maxTotalCharacters <= 0) {
            throw new IllegalArgumentException("maxTotalCharacters must be positive.");
        }
    }
}
