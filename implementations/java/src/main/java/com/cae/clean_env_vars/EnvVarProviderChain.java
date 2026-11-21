package com.cae.clean_env_vars;

import com.cae.clean_mapped_exceptions.specifics.InternalMappedException;

import java.util.ArrayList;
import java.util.List;

/** Holds the ordered provider chain shared by all environment variable lookups. */
public final class EnvVarProviderChain {
    /** The chain configured during application setup. */
    public static final EnvVarProviderChain SINGLETON = new EnvVarProviderChain();

    private final List<EnvVarProvider> providers = new ArrayList<>(List.of(new SystemEnvironmentProvider()));
    private boolean locked;

    private EnvVarProviderChain() {
    }

    /**
     * Appends a custom provider after the providers already registered.
     * @param provider the non-null provider to register
     * @return whether the provider was added
     * @throws InternalMappedException if the chain is locked or the provider is null
     */
    public synchronized boolean add(EnvVarProvider provider) {
        validateRegistrationChange(provider);
        if (provider instanceof SystemEnvironmentProvider || providers.contains(provider)) {
            return false;
        }
        return providers.add(provider);
    }

    /**
     * Removes a custom provider. The built-in process environment provider is permanent.
     * @param provider the non-null provider to remove
     * @return whether the provider was removed
     * @throws InternalMappedException if the chain is locked or the provider is null
     */
    public synchronized boolean remove(EnvVarProvider provider) {
        validateRegistrationChange(provider);
        return providers.subList(1, providers.size()).remove(provider);
    }

    /** Freezes provider registration for the lifetime of this chain. */
    public synchronized void lock() {
        locked = true;
    }

    /**
     * Reports whether provider registration has been frozen.
     * @return whether the chain is locked
     */
    public synchronized boolean isLocked() {
        return locked;
    }

    synchronized List<EnvVarProvider> snapshot() {
        return List.copyOf(providers);
    }

    private void validateRegistrationChange(EnvVarProvider provider) {
        if (locked) {
            throw new InternalMappedException("The environment variable provider chain is locked");
        }
        if (provider == null) {
            throw new InternalMappedException("An environment variable provider is required");
        }
    }
}
