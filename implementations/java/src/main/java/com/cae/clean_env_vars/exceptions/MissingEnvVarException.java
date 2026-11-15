package com.cae.clean_env_vars.exceptions;

import com.cae.clean_mapped_exceptions.specifics.InternalMappedException;

import java.util.Objects;

/** Indicates that all consulted providers reported a required variable absent. */
public final class MissingEnvVarException extends InternalMappedException {
    /**
     * Identifies the missing configuration requirement.
     * @param name the non-null variable name
     */
    public MissingEnvVarException(String name) {
        super("Required configuration is missing",
                "No provider supplied the required variable '" + Objects.requireNonNull(name, "name") + "'");
    }
}
