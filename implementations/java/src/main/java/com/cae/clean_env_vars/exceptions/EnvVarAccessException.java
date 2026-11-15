package com.cae.clean_env_vars.exceptions;

/** A source could not be accessed, leaving the configuration requirement unresolved. */
public final class EnvVarAccessException extends UnexpectedException {
    /**
     * Retains an inaccessible provider's failure.
     * @param original the provider-access failure
     * @param name the variable name
     * @param expectedType the requested type
     */
    public EnvVarAccessException(Exception original, String name, Class<?> expectedType) {
        super(original, name, expectedType);
    }
}
