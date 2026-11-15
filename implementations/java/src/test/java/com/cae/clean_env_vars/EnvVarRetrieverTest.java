package com.cae.clean_env_vars;

import com.cae.clean_env_vars.exceptions.EnvVarAccessException;
import com.cae.clean_env_vars.exceptions.InvalidEnvVarException;
import com.cae.clean_env_vars.exceptions.MissingEnvVarException;
import com.cae.clean_env_vars.exceptions.UnexpectedException;
import com.cae.clean_mapped_exceptions.specifics.InternalMappedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EnvVarRetrieverTest {
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
    void defaultRetrieverAndExplicitEnvironmentProviderReadTheActualEnvironment() {
        var provider = new SystemEnvironmentProvider();
        var retriever = new EnvVarRetriever(List.of(provider));
        var defaults = new EnvVarRetriever();
        assertAll(
                () -> assertEquals(Optional.of("test-value"), provider.get("CLEAN_ENV_VARS_TEST_TEXT")),
                () -> assertEquals(Optional.of(""), provider.get("CLEAN_ENV_VARS_TEST_EMPTY")),
                () -> assertEquals("test-value", retriever.getRequired("CLEAN_ENV_VARS_TEST_TEXT", String.class)),
                () -> assertEquals(123, defaults.getRequired("CLEAN_ENV_VARS_TEST_INTEGER", Integer.class)),
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
                () -> assertEquals(Optional.of(""), new EnvVarRetriever()
                        .getOptional("CLEAN_ENV_VARS_TEST_EMPTY", String.class)));
    }

    @Test
    void requiredOptionalAndDefaultRepresentDifferentAbsentOutcomes() {
        var retriever = new EnvVarRetriever(List.of(name -> Optional.empty()));
        var missing = assertThrows(MissingEnvVarException.class,
                () -> retriever.getRequired("PORT", Integer.class));
        assertAll(
                () -> assertInstanceOf(InternalMappedException.class, missing),
                () -> assertTrue(missing.getDetails().orElseThrow().contains("PORT")),
                () -> assertTrue(retriever.getOptional("PORT", Integer.class).isEmpty()),
                () -> assertEquals(8080, retriever.getOrDefault("PORT", Integer.class, 8080)),
                () -> assertEquals("default", retriever.getOrDefault("TEXT", String.class, "default")),
                () -> assertFalse(retriever.getOrDefault("FLAG", Boolean.class, false)));
    }

    @Test
    void firstFoundValueWinsAndReceivesTheExactName() {
        var requested = new ArrayList<String>();
        var retriever = new EnvVarRetriever(List.of(
                name -> {
                    requested.add("first:" + name);
                    return Optional.empty();
                },
                name -> {
                    requested.add("second:" + name);
                    return Optional.of("42");
                },
                name -> fail("Lookup must stop after the first found value")));
        assertAll(
                () -> assertEquals(42, retriever.getRequired("PORT", Integer.class)),
                () -> assertEquals(List.of("first:PORT", "second:PORT"), requested));
    }

    @Test
    void customChainDoesNotImplicitlyInsertProcessEnvironment() {
        var retriever = values(Map.of());
        assertTrue(retriever.getOptional("CLEAN_ENV_VARS_TEST_TEXT", String.class).isEmpty());
    }

    @Test
    void environmentProviderCanAppearAfterApplicationProvider() {
        var retriever = new EnvVarRetriever(List.<EnvVarProvider>of(
                name -> Optional.empty(), new SystemEnvironmentProvider()));
        assertEquals("test-value", retriever.getRequired("CLEAN_ENV_VARS_TEST_TEXT", String.class));
    }

    @Test
    void foundValuesOverrideDefaultsWithoutTrimming() {
        var retriever = values(Map.of("TEXT", "  value  ", "COUNT", "7", "FLAG", "false"));
        assertAll(
                () -> assertEquals("  value  ", retriever.getOrDefault("TEXT", String.class, "fallback")),
                () -> assertEquals(7, retriever.getOrDefault("COUNT", Integer.class, 9)),
                () -> assertFalse(retriever.getOrDefault("FLAG", Boolean.class, true)));
    }

    @Test
    void emptyStringIsFoundAndDoesNotAdvanceTheChainOrSelectDefault() {
        var retriever = new EnvVarRetriever(List.of(
                name -> Optional.of(""),
                name -> fail("An empty value must stop lookup")));
        assertAll(
                () -> assertEquals("", retriever.getOrDefault("TEXT", String.class, "fallback")),
                () -> assertThrows(InvalidEnvVarException.class,
                        () -> retriever.getOrDefault("COUNT", Integer.class, 9)),
                () -> assertThrows(InvalidEnvVarException.class,
                        () -> retriever.getOptional("FLAG", Boolean.class)));
    }

    @Test
    void invalidFoundValuesDoNotAdvanceTheChainOrSelectDefault() {
        var retriever = new EnvVarRetriever(List.of(
                name -> Optional.of("invalid"),
                name -> fail("Conversion failure must not fall through to another source")));
        assertThrows(InvalidEnvVarException.class,
                () -> retriever.getOrDefault("PORT", Integer.class, 8080));
    }

    @ParameterizedTest
    @CsvSource({"0,0", "+42,42", "-42,-42", "0007,7", "-0,0",
            "2147483647,2147483647", "-2147483648,-2147483648"})
    void parsesSignedDecimalIntegers(String text, int expected) {
        assertEquals(expected, values(Map.of("N", text)).getRequired("N", Integer.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", " 1", "1 ", "1.5", "1e2", "0x10", "1_000", "+", "--1", "١", "secret-value"})
    void rejectsInvalidIntegerFormsWithoutLeakingValues(String text) {
        var failure = assertThrows(InvalidEnvVarException.class,
                () -> values(Map.of("COUNT", text)).getRequired("COUNT", Integer.class));
        assertAll(
                () -> assertInstanceOf(IllegalArgumentException.class, failure.getCause()),
                () -> assertEquals("Expected a signed base-10 integer without whitespace", failure.getCause().getMessage()),
                () -> assertTrue(failure.getDetails().orElseThrow().contains("COUNT")),
                () -> assertTrue(failure.getDetails().orElseThrow().contains("Integer")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2147483648", "-2147483649", "999999999999999999999999999999999"})
    void rejectsIntegerOverflowWithoutIncludingTheSuppliedValue(String text) {
        var failure = assertThrows(InvalidEnvVarException.class,
                () -> values(Map.of("COUNT", text)).getRequired("COUNT", Integer.class));
        assertAll(
                () -> assertInstanceOf(ArithmeticException.class, failure.getCause()),
                () -> assertFalse(failure.getMessage().contains(text)),
                () -> assertFalse(failure.toString().contains(text)));
    }

    @ParameterizedTest
    @CsvSource({"true,true", "TRUE,true", "TrUe,true", "false,false", "FALSE,false", "FaLsE,false"})
    void parsesOnlyRecognizedBooleanWordsIgnoringCase(String text, boolean expected) {
        assertEquals(expected, values(Map.of("FLAG", text)).getRequired("FLAG", Boolean.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "1", "0", "yes", "no", " true", "false ", "secret-value"})
    void rejectsUnrecognizedBooleansInsteadOfSilentlyReturningFalse(String text) {
        var failure = assertThrows(InvalidEnvVarException.class,
                () -> values(Map.of("FLAG", text)).getOrDefault("FLAG", Boolean.class, false));
        assertEquals("Expected true or false without whitespace", failure.getCause().getMessage());
    }

    @Test
    void stringValuesArePreservedVerbatim() {
        var secret = "private-config-value-例";
        var retriever = values(Map.of("TEXT", secret));
        assertEquals(secret, retriever.getRequired("TEXT", String.class));
    }

    @Test
    void providerFailuresStopLookupByDefaultAndRetainOriginalCause() {
        var original = new IOException("Service unavailable");
        var retriever = new EnvVarRetriever(List.of(
                name -> { throw original; },
                name -> fail("Fail-fast lookup must stop")));
        var failure = assertThrows(EnvVarAccessException.class,
                () -> retriever.getOrDefault("REMOTE", String.class, "default"));
        assertAll(
                () -> assertInstanceOf(UnexpectedException.class, failure),
                () -> assertSame(original, failure.getCause()),
                () -> assertSame(original, failure.getOriginalException().orElseThrow()),
                () -> assertTrue(failure.getDetails().orElseThrow().contains("REMOTE")));
    }

    @Test
    void explicitContinuationCanRecoverFromAnInaccessibleProvider() {
        var retriever = new EnvVarRetriever(List.of(
                name -> { throw new IOException("Remote unavailable"); },
                name -> Optional.of("17")),
                ProviderFailurePolicy.CONTINUE);
        assertEquals(17, retriever.getRequired("COUNT", Integer.class));
    }

    @Test
    void continuationDoesNotTurnUnresolvedAccessFailuresIntoAbsence() {
        var original = new IOException("First unavailable");
        var second = new IllegalStateException("Second unavailable");
        var retriever = new EnvVarRetriever(List.of(
                name -> { throw original; },
                name -> Optional.empty(),
                name -> { throw second; }),
                ProviderFailurePolicy.CONTINUE);
        var failure = assertThrows(EnvVarAccessException.class,
                () -> retriever.getOrDefault("REMOTE", Integer.class, 42));
        assertAll(
                () -> assertSame(original, failure.getCause()),
                () -> assertEquals(List.of(second), List.of(failure.getSuppressed())),
                () -> assertThrows(EnvVarAccessException.class,
                        () -> retriever.getOptional("REMOTE", String.class)));
    }

    @Test
    void continuationWithOnlyAbsentProvidersAllowsOptionalAndDefault() {
        var retriever = new EnvVarRetriever(List.of(name -> Optional.empty()),
                ProviderFailurePolicy.CONTINUE);
        assertAll(
                () -> assertTrue(retriever.getOptional("COUNT", Integer.class).isEmpty()),
                () -> assertEquals(9, retriever.getOrDefault("COUNT", Integer.class, 9)));
    }

    @Test
    void continuationStillRejectsTheFirstFoundInvalidValue() {
        var retriever = new EnvVarRetriever(List.of(
                name -> { throw new IOException("Unavailable"); },
                name -> Optional.of("not-an-integer"),
                name -> fail("A found value must be converted without source fallback")),
                ProviderFailurePolicy.CONTINUE);
        assertThrows(InvalidEnvVarException.class, () -> retriever.getRequired("COUNT", Integer.class));
    }

    @Test
    void invalidProviderResultIsAnAccessFailureRatherThanAbsence() {
        var retriever = new EnvVarRetriever(List.of(name -> null));
        var failure = assertThrows(EnvVarAccessException.class,
                () -> retriever.getOptional("COUNT", Integer.class));
        assertInstanceOf(NullPointerException.class, failure.getCause());
    }

    @Test
    void interruptionRestoresFlagAndStopsEvenAContinuingChain() {
        var interrupted = new InterruptedException("Canceled");
        var retriever = new EnvVarRetriever(List.of(
                name -> { throw interrupted; },
                name -> fail("Interruption must stop provider lookup")),
                ProviderFailurePolicy.CONTINUE);
        try {
            var failure = assertThrows(EnvVarAccessException.class,
                    () -> retriever.getRequired("REMOTE", String.class));
            assertAll(
                    () -> assertTrue(Thread.currentThread().isInterrupted()),
                    () -> assertSame(interrupted, failure.getCause()));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void jvmErrorsPropagateWithoutTryingAnotherProvider() {
        var fatal = new AssertionError("Fatal");
        var retriever = new EnvVarRetriever(List.of(
                name -> { throw fatal; },
                name -> fail("Errors must propagate")),
                ProviderFailurePolicy.CONTINUE);
        assertSame(fatal, assertThrows(AssertionError.class,
                () -> retriever.getRequired("REMOTE", String.class)));
    }

    @Test
    void providerChainIsAnIndependentSnapshot() {
        var providers = new ArrayList<EnvVarProvider>();
        providers.add(name -> Optional.of("first"));
        var retriever = new EnvVarRetriever(providers);
        providers.clear();
        providers.add(name -> Optional.of("replacement"));
        assertEquals("first", retriever.getRequired("TEXT", String.class));
    }

    @Test
    void eachRequestLooksUpItsValueAgainWithoutChangingPreviouslyReturnedValues() {
        var supplied = new AtomicReference<>("before");
        var lookups = new AtomicInteger();
        var retriever = new EnvVarRetriever(List.of(name -> {
            lookups.incrementAndGet();
            return Optional.of(supplied.get());
        }));
        var first = retriever.getRequired("TEXT", String.class);
        supplied.set("after");
        var second = retriever.getRequired("TEXT", String.class);
        assertAll(
                () -> assertEquals("before", first),
                () -> assertEquals("after", second),
                () -> assertEquals(2, lookups.get()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "BAD=NAME", "BAD\0NAME"})
    void rejectsInvalidNamesBeforeConsultingProviders(String name) {
        var retriever = new EnvVarRetriever(List.of(ignored -> fail("Invalid requests must not access providers")));
        assertThrows(RuntimeException.class, () -> retriever.getOptional(name, String.class));
    }

    @Test
    void rejectsUnsupportedTypesBeforeConsultingProviders() {
        var retriever = new EnvVarRetriever(List.of(name -> fail("Unsupported types must not access providers")));
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> retriever.getRequired("N", Long.class)),
                () -> assertThrows(IllegalArgumentException.class, () -> retriever.getRequired("N", int.class)),
                () -> assertThrows(NullPointerException.class, () -> retriever.getOptional("N", null)),
                () -> assertThrows(NullPointerException.class, () -> retriever.getOrDefault("N", String.class, null)));
    }

    @Test
    void rejectsInvalidProviderConfiguration() {
        var absent = (EnvVarProvider) name -> Optional.empty();
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new EnvVarRetriever(null)),
                () -> assertThrows(IllegalArgumentException.class, () -> new EnvVarRetriever(List.of())),
                () -> assertThrows(NullPointerException.class,
                        () -> new EnvVarRetriever(java.util.Arrays.asList(absent, null))),
                () -> assertThrows(NullPointerException.class,
                        () -> new EnvVarRetriever(List.of(absent), null)));
    }

    @Test
    void publicExceptionConstructorsRetainOriginalsAndValidateArguments() {
        var original = new IOException("Unavailable");
        var failure = new UnexpectedException(original, "REMOTE", String.class);
        assertAll(
                () -> assertSame(original, failure.getCause()),
                () -> assertInstanceOf(InternalMappedException.class, failure),
                () -> assertThrows(NullPointerException.class, () -> new MissingEnvVarException(null)),
                () -> assertThrows(NullPointerException.class, () -> new UnexpectedException(null, "N", String.class)),
                () -> assertThrows(NullPointerException.class, () -> new UnexpectedException(original, null, String.class)),
                () -> assertThrows(NullPointerException.class, () -> new UnexpectedException(original, "N", null)));
    }

    private static EnvVarRetriever values(Map<String, String> values) {
        return new EnvVarRetriever(List.of(name -> Optional.ofNullable(values.get(name))));
    }
}
