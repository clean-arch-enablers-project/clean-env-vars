package com.cae.clean_env_vars.exceptions;

import com.cae.clean_mapped_exceptions.specifics.InternalMappedException;

import java.util.Objects;

/** A source could not be accessed, leaving the configuration requirement unresolved. */
public final class EnvVarAccessException extends InternalMappedException {
    /**
     * Retains an inaccessible provider's failure.
     * @param original the non-null provider-access failure
     * @param name the non-null variable name
     * @param expectedType the non-null requested type
     */
    public EnvVarAccessException(Exception original, String name, Class<?> expectedType) {
        super("Configuration could not be resolved",
                "Variable '" + Objects.requireNonNull(name, "name") + "' was requested as "
                        + Objects.requireNonNull(expectedType, "expectedType").getSimpleName(),
                Objects.requireNonNull(original, "original"));
    }
}
