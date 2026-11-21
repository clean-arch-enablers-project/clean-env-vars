package com.cae.clean_env_vars;

import java.util.Optional;

/** Retrieves textual configuration values from an application-selected source. */
@FunctionalInterface
public interface EnvVarProvider {
    /**
     * Looks up a variable without converting its value.
     * Preserve a source interruption as the direct cause of an unchecked exception
     * so the retriever can restore the thread's interrupted flag and stop lookup.
     * @param name the variable name
     * @return a non-null optional; an empty string is a found value
     * @throws RuntimeException if the source cannot be accessed
     */
    Optional<String> get(String name);
}
