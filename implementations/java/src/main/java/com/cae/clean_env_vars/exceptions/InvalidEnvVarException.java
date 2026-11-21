package com.cae.clean_env_vars.exceptions;

import com.cae.clean_mapped_exceptions.specifics.InternalMappedException;

import java.util.Objects;

/** A found value cannot be represented by the requested type. */
public final class InvalidEnvVarException extends InternalMappedException {
    /**
     * Retains a conversion failure without copying the variable's value.
     * @param original the non-null conversion failure
     * @param name the non-null variable name
     * @param expectedType the non-null requested type
     */
    public InvalidEnvVarException(Exception original, String name, Class<?> expectedType) {
        super("Configuration could not be resolved",
                "Variable '" + Objects.requireNonNull(name, "name") + "' was requested as "
                        + Objects.requireNonNull(expectedType, "expectedType").getSimpleName(),
                Objects.requireNonNull(original, "original"));
    }
}
