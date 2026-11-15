package com.cae.clean_env_vars;

/** Controls provider-access failures independently of missing or invalid values. */
public enum ProviderFailurePolicy {
    /** Stop lookup at the first inaccessible provider. */
    FAIL_FAST,
    /**
     * Continue to later providers. If none supplies a value, report the access failure
     * rather than treating the lookup as absent or selecting a default.
     */
    CONTINUE
}
