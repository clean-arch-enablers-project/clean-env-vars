package com.cae.clean_env_vars;

import com.cae.clean_env_vars.exceptions.EnvVarAccessException;
import com.cae.clean_env_vars.exceptions.InvalidEnvVarException;
import com.cae.clean_env_vars.exceptions.MissingEnvVarException;
import com.cae.clean_mapped_exceptions.specifics.InternalMappedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.cae.clean_env_vars.EnvVarRetriever.*;
import static org.junit.jupiter.api.Assertions.*;

@Isolated
class EnvVarRetrieverTest {
    private static final String TEST_PORT = "CLEAN_ENV_VARS_TEST_FIXTURE_PORT";
    private static final String TEST_TEXT = "CLEAN_ENV_VARS_TEST_FIXTURE_TEXT";
    private static final String TEST_FLAG = "CLEAN_ENV_VARS_TEST_FIXTURE_FLAG";
    private static final String TEST_COUNT = "CLEAN_ENV_VARS_TEST_FIXTURE_COUNT";
    private static final String TEST_N = "CLEAN_ENV_VARS_TEST_FIXTURE_N";
    private static final String TEST_REMOTE = "CLEAN_ENV_VARS_TEST_FIXTURE_REMOTE";

    private final List<EnvVarProvider> registeredProviders = new ArrayList<>();

    @AfterEach
    void removeRegisteredProviders() {
        registeredProviders.forEach(EnvVarProviderChain.SINGLETON::remove);
    }

    @Test
    void staticFacadeReadsAndConvertsActualEnvironment() {
        assertAll(
                () -> assertEquals("test-value", EnvVarRetriever.getEnvVarByNameAsString("CLEAN_ENV_VARS_TEST_TEXT")),
                () -> assertEquals(123, EnvVarRetriever.getEnvVarByNameAsInteger("CLEAN_ENV_VARS_TEST_INTEGER")),
                () -> assertTrue(EnvVarRetriever.getEnvVarByNameAsBoolean("CLEAN_ENV_VARS_TEST_BOOLEAN")),
                () -> assertEquals(123, EnvVarRetriever.getEnvVarByName("CLEAN_ENV_VARS_TEST_INTEGER", Integer.class)),
                () -> assertEquals("", EnvVarRetriever.getEnvVarByNameAsString("CLEAN_ENV_VARS_TEST_EMPTY")),
                () -> assertThrows(InvalidEnvVarException.class,
                        () -> EnvVarRetriever.getEnvVarByNameAsInteger("CLEAN_ENV_VARS_TEST_TEXT")));
    }

    @Test
    void defaultChainAndEnvironmentProviderReadTheActualEnvironment() {
        var provider = new SystemEnvironmentProvider();
        assertAll(
                () -> assertEquals(Optional.of("test-value"), provider.get("CLEAN_ENV_VARS_TEST_TEXT")),
                () -> assertEquals(Optional.of(""), provider.get("CLEAN_ENV_VARS_TEST_EMPTY")),
                () -> assertEquals("test-value", getRequired("CLEAN_ENV_VARS_TEST_TEXT", String.class)),
                () -> assertEquals(123, getRequired("CLEAN_ENV_VARS_TEST_INTEGER", Integer.class)),
                () -> assertThrows(NullPointerException.class, () -> provider.get(null)));
    }

    @Test
    void absenceInActualEnvironmentIsDistinctFromEmpty() {
        var name = "CLEAN_ENV_VARS_TEST_ABSENT";
        while (System.getenv(name) != null) {
            name += "_";
        }
        var missingName = name;
        var provider = new SystemEnvironmentProvider();
        assertAll(
                () -> assertTrue(provider.get(missingName).isEmpty()),
                () -> assertThrows(MissingEnvVarException.class,
                        () -> EnvVarRetriever.getEnvVarByNameAsString(missingName)),
                () -> assertEquals(Optional.of(""), getOptional("CLEAN_ENV_VARS_TEST_EMPTY", String.class)));
    }

    @Test
    void requiredOptionalAndDefaultRepresentDifferentAbsentOutcomes() {
        register(name -> Optional.empty());
        var missing = assertThrows(MissingEnvVarException.class,
                () -> getRequired(TEST_PORT, Integer.class));
        assertAll(
                () -> assertInstanceOf(InternalMappedException.class, missing),
                () -> assertTrue(missing.getDetails().orElseThrow().contains(TEST_PORT)),
                () -> assertTrue(getOptional(TEST_PORT, Integer.class).isEmpty()),
                () -> assertEquals(8080, getOrDefault(TEST_PORT, Integer.class, 8080)),
                () -> assertEquals("default", getOrDefault(TEST_TEXT, String.class, "default")),
                () -> assertFalse(getOrDefault(TEST_FLAG, Boolean.class, false)));
    }

    @Test
    void firstFoundValueWinsAndReceivesTheExactName() {
        var requested = new ArrayList<String>();
        register(
                name -> {
                    requested.add("first:" + name);
                    return Optional.empty();
                },
                name -> {
                    requested.add("second:" + name);
                    return Optional.of("42");
                },
                name -> fail("Lookup must stop after the first found value"));
        assertAll(
                () -> assertEquals(42, getRequired(TEST_PORT, Integer.class)),
                () -> assertEquals(List.of("first:" + TEST_PORT, "second:" + TEST_PORT), requested));
    }

    @Test
    void staticLookupMethodsUseRegisteredProviders() {
        values(Map.of("CLEAN_ENV_VARS_TEST_CUSTOM_TEXT", "custom", "CLEAN_ENV_VARS_TEST_CUSTOM_FLAG", "true",
                "CLEAN_ENV_VARS_TEST_CUSTOM_NUMBER", "17"));
        assertAll(
                () -> assertEquals("custom", getEnvVarByNameAsString("CLEAN_ENV_VARS_TEST_CUSTOM_TEXT")),
                () -> assertTrue(getEnvVarByNameAsBoolean("CLEAN_ENV_VARS_TEST_CUSTOM_FLAG")),
                () -> assertEquals(17, getEnvVarByNameAsInteger("CLEAN_ENV_VARS_TEST_CUSTOM_NUMBER")),
                () -> assertEquals(17, getEnvVarByName("CLEAN_ENV_VARS_TEST_CUSTOM_NUMBER", Integer.class)));
    }

    @Test
    void environmentProviderHasPriorityOverAppendedProviders() {
        register(name -> fail("The process environment already supplied this value"));
        assertEquals("test-value", getRequired("CLEAN_ENV_VARS_TEST_TEXT", String.class));
    }

    @Test
    void foundValuesOverrideDefaultsWithoutTrimming() {
        values(Map.of(TEST_TEXT, "  value  ", TEST_COUNT, "7", TEST_FLAG, "false"));
        assertAll(
                () -> assertEquals("  value  ", getOrDefault(TEST_TEXT, String.class, "fallback")),
                () -> assertEquals(7, getOrDefault(TEST_COUNT, Integer.class, 9)),
                () -> assertFalse(getOrDefault(TEST_FLAG, Boolean.class, true)));
    }

    @Test
    void emptyStringIsFoundAndDoesNotAdvanceTheChainOrSelectDefault() {
        register(
                name -> Optional.of(""),
                name -> fail("An empty value must stop lookup"));
        assertAll(
                () -> assertEquals("", getOrDefault(TEST_TEXT, String.class, "fallback")),
                () -> assertThrows(InvalidEnvVarException.class,
                        () -> getOrDefault(TEST_COUNT, Integer.class, 9)),
                () -> assertThrows(InvalidEnvVarException.class,
                        () -> getOptional(TEST_FLAG, Boolean.class)));
    }

    @Test
    void invalidFoundValuesDoNotAdvanceTheChainOrSelectDefault() {
        register(
                name -> Optional.of("invalid"),
                name -> fail("Conversion failure must not fall through to another source"));
        assertThrows(InvalidEnvVarException.class,
                () -> getOrDefault(TEST_PORT, Integer.class, 8080));
    }

    @ParameterizedTest
    @CsvSource({"0,0", "+42,42", "-42,-42", "0007,7", "-0,0",
            "2147483647,2147483647", "-2147483648,-2147483648"})
    void parsesSignedDecimalIntegers(String text, int expected) {
        values(Map.of(TEST_N, text));
        assertEquals(expected, getRequired(TEST_N, Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " 1", "1 ", "1.5", "1e2", "0x10", "1_000", "+", "--1", "١", "secret-value"})
    void rejectsInvalidIntegerFormsWithoutLeakingValues(String text) {
        values(Map.of(TEST_COUNT, text));
        var failure = assertThrows(InvalidEnvVarException.class,
                () -> getRequired(TEST_COUNT, Integer.class));
        assertAll(
                () -> assertInstanceOf(IllegalArgumentException.class, failure.getCause()),
                () -> assertEquals("Expected a signed base-10 integer without whitespace", failure.getCause().getMessage()),
                () -> assertTrue(failure.getDetails().orElseThrow().contains(TEST_COUNT)),
                () -> assertTrue(failure.getDetails().orElseThrow().contains("Integer")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "-2147483649", "999999999999999999999999999999999"})
    void rejectsIntegerOverflowWithoutIncludingTheSuppliedValue(String text) {
        values(Map.of(TEST_COUNT, text));
        var failure = assertThrows(InvalidEnvVarException.class,
                () -> getRequired(TEST_COUNT, Integer.class));
        assertAll(
                () -> assertInstanceOf(ArithmeticException.class, failure.getCause()),
                () -> assertFalse(failure.getMessage().contains(text)),
                () -> assertFalse(failure.toString().contains(text)));
    }

    @ParameterizedTest
    @CsvSource({"true,true", "TRUE,true", "TrUe,true", "false,false", "FALSE,false", "FaLsE,false"})
    void parsesOnlyRecognizedBooleanWordsIgnoringCase(String text, boolean expected) {
        values(Map.of(TEST_FLAG, text));
        assertEquals(expected, getRequired(TEST_FLAG, Boolean.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1", "0", "yes", "no", " true", "false ", "secret-value"})
    void rejectsUnrecognizedBooleansInsteadOfSilentlyReturningFalse(String text) {
        values(Map.of(TEST_FLAG, text));
        var failure = assertThrows(InvalidEnvVarException.class,
                () -> getOrDefault(TEST_FLAG, Boolean.class, false));
        assertEquals("Expected true or false without whitespace", failure.getCause().getMessage());
    }

    @Test
    void stringValuesArePreservedVerbatim() {
        var secret = "private-config-value-例";
        values(Map.of(TEST_TEXT, secret));
        assertEquals(secret, getRequired(TEST_TEXT, String.class));
    }

    @Test
    void unresolvedProviderFailureRetainsOriginalCause() {
        var ioFailure = new IOException("Service unavailable");
        var original = new UncheckedIOException(ioFailure);
        register(name -> { throw original; });
        var failure = assertThrows(EnvVarAccessException.class,
                () -> getOrDefault(TEST_REMOTE, String.class, "default"));
        assertAll(
                () -> assertSame(original, failure.getCause()),
                () -> assertSame(ioFailure, failure.getCause().getCause()),
                () -> assertSame(original, failure.getOriginalException().orElseThrow()),
                () -> assertTrue(failure.getDetails().orElseThrow().contains(TEST_REMOTE)));
    }

    @Test
    void lookupCanRecoverFromAnInaccessibleProvider() {
        register(
                name -> { throw new IllegalStateException("Remote unavailable"); },
                name -> Optional.of("17"));
        assertEquals(17, getRequired(TEST_COUNT, Integer.class));
    }

    @Test
    void continuationDoesNotTurnUnresolvedAccessFailuresIntoAbsence() {
        var original = new IllegalStateException("First unavailable");
        var second = new IllegalStateException("Second unavailable");
        register(
                name -> { throw original; },
                name -> Optional.empty(),
                name -> { throw second; });
        var failure = assertThrows(EnvVarAccessException.class,
                () -> getOrDefault(TEST_REMOTE, Integer.class, 42));
        assertAll(
                () -> assertSame(original, failure.getCause()),
                () -> assertEquals(List.of(second), List.of(failure.getSuppressed())),
                () -> assertThrows(EnvVarAccessException.class,
                        () -> getOptional(TEST_REMOTE, String.class)));
    }

    @Test
    void continuationWithOnlyAbsentProvidersAllowsOptionalAndDefault() {
        register(name -> Optional.empty());
        assertAll(
                () -> assertTrue(getOptional(TEST_COUNT, Integer.class).isEmpty()),
                () -> assertEquals(9, getOrDefault(TEST_COUNT, Integer.class, 9)));
    }

    @Test
    void continuationStillRejectsTheFirstFoundInvalidValue() {
        register(
                name -> { throw new IllegalStateException("Unavailable"); },
                name -> Optional.of("not-an-integer"),
                name -> fail("A found value must be converted without source fallback"));
        assertThrows(InvalidEnvVarException.class, () -> getRequired(TEST_COUNT, Integer.class));
    }

    @Test
    void invalidProviderResultIsAnAccessFailureRatherThanAbsence() {
        register(name -> null);
        var failure = assertThrows(EnvVarAccessException.class,
                () -> getOptional(TEST_COUNT, Integer.class));
        assertInstanceOf(NullPointerException.class, failure.getCause());
    }

    @Test
    void interruptionRestoresFlagAndStopsEvenAContinuingChain() {
        var interrupted = new InterruptedException("Canceled");
        var original = new IllegalStateException(interrupted);
        register(
                name -> { throw original; },
                name -> fail("Interruption must stop provider lookup"));
        try {
            var failure = assertThrows(EnvVarAccessException.class,
                    () -> getRequired(TEST_REMOTE, String.class));
            assertAll(
                    () -> assertTrue(Thread.currentThread().isInterrupted()),
                    () -> assertSame(original, failure.getCause()),
                    () -> assertSame(interrupted, failure.getCause().getCause()));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void jvmErrorsPropagateWithoutTryingAnotherProvider() {
        var fatal = new AssertionError("Fatal");
        register(
                name -> { throw fatal; },
                name -> fail("Errors must propagate"));
        assertSame(fatal, assertThrows(AssertionError.class,
                () -> getRequired(TEST_REMOTE, String.class)));
    }

    @Test
    void lookupSeesProviderChangesOnTheNextRequest() {
        var chain = EnvVarProviderChain.SINGLETON;
        EnvVarProvider temporary = name -> Optional.of("first");
        register(temporary);
        assertEquals("first", getRequired(TEST_TEXT, String.class));
        assertTrue(chain.remove(temporary));
        assertTrue(getOptional(TEST_TEXT, String.class).isEmpty());
        register(name -> Optional.of("replacement"));
        assertEquals("replacement", getRequired(TEST_TEXT, String.class));
    }

    @Test
    void lookupKeepsItsProviderOrderWhenRegistrationChangesMidRequest() {
        var chain = EnvVarProviderChain.SINGLETON;
        EnvVarProvider laterProvider = name -> Optional.of("first request");
        register(name -> {
            chain.remove(laterProvider);
            return Optional.empty();
        }, laterProvider);

        assertEquals("first request", getRequired(TEST_TEXT, String.class));
        assertTrue(getOptional(TEST_TEXT, String.class).isEmpty());
    }

    @Test
    void chainRejectsDuplicatesAndKeepsTheProcessEnvironmentProvider() {
        var chain = EnvVarProviderChain.SINGLETON;
        var systemProvider = assertInstanceOf(SystemEnvironmentProvider.class, chain.snapshot().getFirst());
        var lookups = new AtomicInteger();
        EnvVarProvider provider = name -> {
            lookups.incrementAndGet();
            return Optional.empty();
        };
        register(provider);
        assertAll(
                () -> assertFalse(chain.add(provider)),
                () -> assertFalse(chain.add(new SystemEnvironmentProvider())),
                () -> assertFalse(chain.remove(systemProvider)),
                () -> assertEquals(List.of(systemProvider, provider), chain.snapshot()),
                () -> assertTrue(getOptional(TEST_TEXT, String.class).isEmpty()),
                () -> assertEquals(1, lookups.get()),
                () -> assertThrows(InternalMappedException.class, () -> chain.add(null)),
                () -> assertThrows(InternalMappedException.class, () -> chain.remove(null)));
    }

    @Test
    void lockingFreezesRegistrationAndPreservesProviders() throws ReflectiveOperationException {
        // Exercise the one-way lock without permanently changing the test JVM's shared chain.
        var constructor = EnvVarProviderChain.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        var chain = constructor.newInstance();
        EnvVarProvider provider = name -> Optional.empty();
        assertTrue(chain.add(provider));
        var beforeLock = chain.snapshot();
        assertFalse(chain.isLocked());

        chain.lock();
        chain.lock();

        assertAll(
                () -> assertTrue(chain.isLocked()),
                () -> assertThrows(InternalMappedException.class, () -> chain.add(name -> Optional.empty())),
                () -> assertThrows(InternalMappedException.class, () -> chain.remove(provider)),
                () -> assertEquals(beforeLock, chain.snapshot()));
    }

    @Test
    void eachRequestLooksUpItsValueAgainWithoutChangingPreviouslyReturnedValues() {
        var supplied = new AtomicReference<>("before");
        var lookups = new AtomicInteger();
        register(name -> {
            lookups.incrementAndGet();
            return Optional.of(supplied.get());
        });
        var first = getRequired(TEST_TEXT, String.class);
        supplied.set("after");
        var second = getRequired(TEST_TEXT, String.class);
        assertAll(
                () -> assertEquals("before", first),
                () -> assertEquals("after", second),
                () -> assertEquals(2, lookups.get()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "BAD=NAME", "BAD\0NAME"})
    void rejectsInvalidNamesBeforeConsultingProviders(String name) {
        register(ignored -> fail("Invalid requests must not access providers"));
        assertThrows(RuntimeException.class, () -> getOptional(name, String.class));
    }

    @Test
    void rejectsUnsupportedTypesBeforeConsultingProviders() {
        register(name -> fail("Unsupported types must not access providers"));
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> getRequired(TEST_N, Long.class)),
                () -> assertThrows(IllegalArgumentException.class, () -> getRequired(TEST_N, int.class)),
                () -> assertThrows(NullPointerException.class, () -> getOptional(TEST_N, null)),
                () -> assertThrows(NullPointerException.class, () -> getOrDefault(TEST_N, String.class, null)));
    }

    @Test
    void accessExceptionConstructorRetainsCauseAndValidatesArguments() {
        var original = new IOException("Unavailable");
        var failure = new EnvVarAccessException(original, TEST_REMOTE, String.class);
        assertAll(
                () -> assertSame(original, failure.getCause()),
                () -> assertSame(InternalMappedException.class, EnvVarAccessException.class.getSuperclass()),
                () -> assertThrows(NullPointerException.class, () -> new EnvVarAccessException(null, TEST_REMOTE, String.class)),
                () -> assertThrows(NullPointerException.class, () -> new EnvVarAccessException(original, null, String.class)),
                () -> assertThrows(NullPointerException.class, () -> new EnvVarAccessException(original, TEST_REMOTE, null)));
    }

    @Test
    void invalidExceptionConstructorRetainsCauseAndValidatesArguments() {
        var original = new NumberFormatException();
        var failure = new InvalidEnvVarException(original, TEST_PORT, Integer.class);
        assertAll(
                () -> assertSame(original, failure.getCause()),
                () -> assertSame(InternalMappedException.class, InvalidEnvVarException.class.getSuperclass()),
                () -> assertThrows(NullPointerException.class, () -> new InvalidEnvVarException(null, TEST_PORT, Integer.class)),
                () -> assertThrows(NullPointerException.class, () -> new InvalidEnvVarException(original, null, Integer.class)),
                () -> assertThrows(NullPointerException.class, () -> new InvalidEnvVarException(original, TEST_PORT, null)));
    }

    @Test
    void missingExceptionConstructorRequiresName() {
        assertThrows(NullPointerException.class, () -> new MissingEnvVarException(null));
    }

    private void values(Map<String, String> values) {
        register(name -> Optional.ofNullable(values.get(name)));
    }

    private void register(EnvVarProvider... providers) {
        for (var provider : providers) {
            assertTrue(EnvVarProviderChain.SINGLETON.add(provider));
            registeredProviders.add(provider);
        }
    }
}
