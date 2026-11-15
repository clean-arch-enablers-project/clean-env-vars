package com.cae.clean_env_vars.exceptions;

/** A found value cannot be represented by the requested type. */
public final class InvalidEnvVarException extends UnexpectedException {
    /**
     * Retains a conversion failure without copying the variable's value.
     * @param original the conversion failure
     * @param name the variable name
     * @param expectedType the requested type
     */
    public InvalidEnvVarException(Exception original, String name, Class<?> expectedType) {
        super(original, name, expectedType);
    }
}
