package com.cae.clean_env_vars.exceptions;

import com.cae.clean_mapped_exceptions.specifics.InternalMappedException;

import java.util.Objects;

/**
 * Base failure for invalid or inaccessible configuration.
 * Diagnostic attributes identify the variable and type without copying the supplied value.
 * Original provider exceptions remain diagnostic data and may contain sensitive information.
 */
public class UnexpectedException extends InternalMappedException {
    /**
     * Retains a conversion or access failure as the standard Java cause.
     * @param original the non-null underlying failure
     * @param name the non-null variable name
     * @param expectedType the non-null requested type
     */
    public UnexpectedException(Exception original, String name, Class<?> expectedType) {
        super("Configuration could not be resolved",
                "Variable '" + Objects.requireNonNull(name, "name") + "' was requested as "
                        + Objects.requireNonNull(expectedType, "expectedType").getSimpleName(),
                Objects.requireNonNull(original, "original"));
    }
}
