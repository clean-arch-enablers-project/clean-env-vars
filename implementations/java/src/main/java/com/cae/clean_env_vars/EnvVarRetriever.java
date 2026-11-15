package com.cae.clean_env_vars;

import com.cae.clean_env_vars.exceptions.EnvVarAccessException;
import com.cae.clean_env_vars.exceptions.InvalidEnvVarException;
import com.cae.clean_env_vars.exceptions.MissingEnvVarException;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves typed configuration through an immutable ordered provider chain.
 * A found value, including an empty string, stops provider lookup before conversion.
 * Provider values are read anew for each request; no configuration cache is maintained.
 */
public final class EnvVarRetriever {
    private static final Pattern INTEGER_TEXT = Pattern.compile("[+-]?[0-9]+");
    private static final EnvVarRetriever ENVIRONMENT = new EnvVarRetriever();

    private final List<EnvVarProvider> providers;
    private final ProviderFailurePolicy failurePolicy;

    /** Creates a fail-fast retriever backed by the actual process environment. */
    public EnvVarRetriever() {
        this(List.of(new SystemEnvironmentProvider()), ProviderFailurePolicy.FAIL_FAST);
    }

    /**
     * Creates a fail-fast retriever for an ordered provider chain.
     * @param providers the non-null, nonempty chain without null entries
     */
    public EnvVarRetriever(List<? extends EnvVarProvider> providers) {
        this(providers, ProviderFailurePolicy.FAIL_FAST);
    }

    /**
     * Creates a retriever with an explicit access-failure policy.
     * @param providers the non-null, nonempty chain without null entries
     * @param failurePolicy the non-null provider-access failure policy
     */
    public EnvVarRetriever(List<? extends EnvVarProvider> providers, ProviderFailurePolicy failurePolicy) {
        this.providers = List.copyOf(providers);
        if (this.providers.isEmpty()) {
            throw new IllegalArgumentException("At least one configuration provider is required");
        }
        this.failurePolicy = Objects.requireNonNull(failurePolicy, "failurePolicy");
    }

    /**
     * Retrieves a required variable from the process environment as text.
     * @param name the variable name
     * @return the supplied text, including an empty string
     */
    public static String getEnvVarByNameAsString(String name) {
        return getEnvVarByName(name, String.class);
    }

    /**
     * Retrieves a required environment variable as a signed 32-bit integer.
     * @param name the variable name
     * @return the converted value
     */
    public static Integer getEnvVarByNameAsInteger(String name) {
        return getEnvVarByName(name, Integer.class);
    }

    /**
     * Retrieves a required environment variable as a strict, case-insensitive boolean.
     * @param name the variable name
     * @return true or false
     */
    public static Boolean getEnvVarByNameAsBoolean(String name) {
        return getEnvVarByName(name, Boolean.class);
    }

    /**
     * Retrieves and converts a required variable from the actual process environment.
     * @param name the variable name
     * @param expectedType String, Integer or Boolean
     * @param <T> the result type
     * @return the converted value
     */
    public static <T> T getEnvVarByName(String name, Class<T> expectedType) {
        return ENVIRONMENT.getRequired(name, expectedType);
    }

    /**
     * Retrieves a required value through this retriever's provider chain.
     * @param name the nonblank variable name, without equals signs or NUL characters
     * @param expectedType String, Integer or Boolean
     * @param <T> the result type
     * @return the converted value
     * @throws MissingEnvVarException if every provider reports absence
     * @throws InvalidEnvVarException if a found value cannot be converted
     * @throws EnvVarAccessException if provider access leaves the request unresolved
     */
    public <T> T getRequired(String name, Class<T> expectedType) {
        return getOptional(name, expectedType).orElseThrow(() -> new MissingEnvVarException(name));
    }

    /**
     * Retrieves an optional value. Invalid or inaccessible values are still failures.
     * @param name the nonblank variable name, without equals signs or NUL characters
     * @param expectedType String, Integer or Boolean
     * @param <T> the result type
     * @return the converted value, or an empty optional if all providers report absence
     * @throws IllegalArgumentException if the name or requested type is unsupported
     */
    public <T> Optional<T> getOptional(String name, Class<T> expectedType) {
        validateRequest(name, expectedType);
        return lookup(name, expectedType).map(value -> convert(name, value, expectedType));
    }

    /**
     * Retrieves a value, selecting the explicit default only when all providers report absence.
     * @param name the variable name
     * @param expectedType String, Integer or Boolean
     * @param defaultValue the non-null typed default
     * @param <T> the result type
     * @return the found value or the supplied default
     */
    public <T> T getOrDefault(String name, Class<T> expectedType, T defaultValue) {
        Objects.requireNonNull(defaultValue, "defaultValue");
        return getOptional(name, expectedType).orElse(defaultValue);
    }

    private static void validateRequest(String name, Class<?> expectedType) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(expectedType, "expectedType");
        if (name.isBlank() || name.indexOf('=') >= 0 || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Variable name must be nonblank and contain neither '=' nor NUL");
        }
        if (expectedType != String.class && expectedType != Integer.class && expectedType != Boolean.class) {
            throw new IllegalArgumentException("Supported configuration types are String, Integer and Boolean");
        }
    }

    private Optional<String> lookup(String name, Class<?> expectedType) {
        EnvVarAccessException accessFailure = null;
        for (var provider : providers) {
            Optional<String> result;
            try {
                result = Objects.requireNonNull(provider.get(name), "Providers must return a non-null Optional");
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new EnvVarAccessException(interruption, name, expectedType);
            } catch (Exception problem) {
                if (failurePolicy == ProviderFailurePolicy.FAIL_FAST) {
                    throw new EnvVarAccessException(problem, name, expectedType);
                }
                if (accessFailure == null) {
                    accessFailure = new EnvVarAccessException(problem, name, expectedType);
                } else {
                    accessFailure.addSuppressed(problem);
                }
                continue;
            }
            if (result.isPresent()) {
                return result;
            }
        }
        if (accessFailure != null) {
            throw accessFailure;
        }
        return Optional.empty();
    }

    private static <T> T convert(String name, String value, Class<T> expectedType) {
        try {
            Object converted;
            if (expectedType == String.class) {
                converted = value;
            } else if (expectedType == Integer.class) {
                converted = parseInteger(value);
            } else {
                converted = parseBoolean(value);
            }
            return expectedType.cast(converted);
        } catch (IllegalArgumentException | ArithmeticException problem) {
            throw new InvalidEnvVarException(problem, name, expectedType);
        }
    }

    private static int parseInteger(String value) {
        if (!INTEGER_TEXT.matcher(value).matches()) {
            throw new IllegalArgumentException("Expected a signed base-10 integer without whitespace");
        }
        return new BigInteger(value).intValueExact();
    }

    private static boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        throw new IllegalArgumentException("Expected true or false without whitespace");
    }
}
