package com.cae.clean_env_vars;

import java.util.Objects;
import java.util.Optional;

/** Reads the actual process environment through {@link System#getenv(String)}. */
public final class SystemEnvironmentProvider implements EnvVarProvider {
    /** Creates a provider backed by the process environment. */
    public SystemEnvironmentProvider() {
        // Environment access is deferred until a value is requested.
    }

    /** {@inheritDoc} */
    @Override
    public Optional<String> get(String name) {
        return Optional.ofNullable(System.getenv(Objects.requireNonNull(name, "name")));
    }
}
