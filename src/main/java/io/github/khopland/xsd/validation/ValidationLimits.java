package io.github.khopland.xsd.validation;

/**
 * Hard limits that bound structural state while validating one XML document.
 *
 * @param maxElementDepth maximum number of simultaneously open elements
 * @param maxDistinctChildNamesPerElement maximum distinct child QNames tracked
 *     under one element
 */
public record ValidationLimits(
        int maxElementDepth,
        int maxDistinctChildNamesPerElement) {

    /** Default limits used by a newly compiled validator. */
    public static final ValidationLimits DEFAULT = new ValidationLimits(256, 100);

    /** Validates that both limits can bound processing. */
    public ValidationLimits {
        if (maxElementDepth <= 0) {
            throw new IllegalArgumentException("maxElementDepth must be positive.");
        }
        if (maxDistinctChildNamesPerElement <= 0) {
            throw new IllegalArgumentException(
                    "maxDistinctChildNamesPerElement must be positive.");
        }
    }
}
