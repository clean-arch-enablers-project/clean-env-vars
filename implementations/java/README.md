# Clean Env Vars for Java

Clean Env Vars is a standalone Java 25 LTS implementation of the [environment-variable concept](../../concept/README.md). It retrieves text from an ordered provider chain and converts it into configuration values. Its only runtime dependency is the published Clean Mapped Exceptions library.

## Dependency and build

```xml
<dependency>
    <groupId>com.clean-arch-enablers</groupId>
    <artifactId>clean-env-vars</artifactId>
    <version>1.0.0-RC1</version>
</dependency>
```

Use Java 25 and Maven 3.9 or newer. From the repository root, run:

```shell
mvn -B -ntp -f implementations/java/pom.xml clean verify
```

For local consumption before this artifact is published, use `mvn -f implementations/java/pom.xml install`. Its declared runtime dependency, `com.clean-arch-enablers:clean-mapped-exceptions:1.0.0-RC1`, is resolved from Maven Central. Workflows require published dependencies and never build another repository's sources.

## Process environment

The static facade reads the actual process environment:

```java
import com.cae.clean_env_vars.EnvVarRetriever;

String host = EnvVarRetriever.getEnvVarByNameAsString("APP_HOST");
Integer port = EnvVarRetriever.getEnvVarByNameAsInteger("APP_PORT");
Boolean enabled = EnvVarRetriever.getEnvVarByNameAsBoolean("APP_ENABLED");
Integer workers = EnvVarRetriever.getEnvVarByName("APP_WORKERS", Integer.class);
```

All four static methods require a value to be present. The generic method performs conversion rather than casting the environment's text to the requested class.

An instance supports required, optional, and defaulted requests:

```java
var configuration = new EnvVarRetriever();

String host = configuration.getRequired("APP_HOST", String.class);
var optionalLabel = configuration.getOptional("APP_LABEL", String.class);
Integer port = configuration.getOrDefault("APP_PORT", Integer.class, 8080);
```

The no-argument constructor uses `SystemEnvironmentProvider` with the `FAIL_FAST` access-failure policy. Each request performs a separate lookup. Already returned values do not change when a provider's data changes.

## Provider chains

An `EnvVarProvider` returns a non-null `Optional<String>` for a name and may throw an exception if its source is inaccessible. `Optional.empty()` means absent; `Optional.of("")` means found and empty.

```java
import com.cae.clean_env_vars.EnvVarProvider;
import com.cae.clean_env_vars.ProviderFailurePolicy;
import com.cae.clean_env_vars.SystemEnvironmentProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;

Map<String, String> applicationValues = Map.of("APP_PORT", "8080");

var configuration = new EnvVarRetriever(
        List.<EnvVarProvider>of(
                new SystemEnvironmentProvider(),
                name -> Optional.ofNullable(applicationValues.get(name))),
        ProviderFailurePolicy.FAIL_FAST);
```

Providers are consulted in order. The first found value stops lookup, before conversion. Empty or invalid values do not advance to another source and do not activate a default.

A supplied chain completely replaces the default chain. Include `SystemEnvironmentProvider` explicitly when process environment access is desired. Chains must be nonempty and contain no null providers. The retriever snapshots the list; later list edits do not change its configuration. Provider implementations remain responsible for their own concurrency and source lifecycle.

Custom providers can use an application's configuration-service clients, including clients with checked exceptions. The library does not require an AWS client, framework, or dependency injection container.

### Access-failure policies

| Policy | Behavior |
| --- | --- |
| `FAIL_FAST` | Stop at the first provider-access exception; this is the default |
| `CONTINUE` | Try later providers; return a later found value, or report an access failure if none supplies a value |

Under `CONTINUE`, inaccessible sources are not treated as evidence that a value is missing. If lookup remains unresolved, the first provider exception is retained as the cause of `EnvVarAccessException`; additional provider exceptions are suppressed on that mapped failure. Optional lookup and defaults do not hide unresolved access failures.

A null provider result is a provider contract failure and follows the access-failure policy. An `InterruptedException` always stops lookup and restores the thread's interrupted flag, even with `CONTINUE`. JVM `Error` instances propagate directly.

## Conversion contract

| Requested class | Accepted input |
| --- | --- |
| `String.class` | Any supplied text, including empty text and whitespace; returned verbatim |
| `Integer.class` | ASCII decimal digits with an optional leading `+` or `-`, within the signed 32-bit range |
| `Boolean.class` | `true` or `false`, ignoring case |

Values are not trimmed. Boolean forms such as `yes`, `1`, and ` false ` are invalid. Integer forms such as `1.5`, `0x10`, and out-of-range numbers are invalid.

Other classes, including primitive class tokens such as `int.class`, are rejected before provider access. Names must be non-null and nonblank and cannot contain `=` or NUL characters. The API preserves valid names as supplied.

Required lookup throws on absence. Optional lookup returns an empty optional only when every provider reports absence. A non-null typed default is used only for that same all-absent outcome. Found values must still convert successfully. Additional validation, such as checking a port's permitted range, belongs to the caller.

## Failure information

| Exception in `com.cae.clean_env_vars.exceptions` | Meaning |
| --- | --- |
| `MissingEnvVarException` | All providers report a required name absent |
| `InvalidEnvVarException` | A found value does not meet the conversion contract |
| `EnvVarAccessException` | Provider access leaves the request unresolved |
| `UnexpectedException` | Shared base for invalid and inaccessible configuration |

These failures extend Clean `InternalMappedException`, matching configuration-resolution failures during application assembly. Application workflows can map them to another category when their context warrants it. Invalid requests, such as null arguments or unsupported class tokens, throw standard argument exceptions.

Diagnostic details identify the variable and requested type without copying its supplied value. Integer conversion validates input before parsing, so invalid input is not echoed by parser exceptions. Conversion and access failures retain their original causes through standard Java cause chaining. Provider-supplied causes may themselves contain sensitive data and belong in controlled diagnostic channels. Use only `getBriefPublicMessage()` for public reporting.

Exception classes rely on Java's generated serialization identifiers; no explicit `serialVersionUID` fields are declared.

## Migration from CAE Env Vars

| CAE API or behavior | Clean API or behavior |
| --- | --- |
| `com.clean-arch-enablers:cae-env-vars` | `com.clean-arch-enablers:clean-env-vars` |
| `com.cae.env_vars` and its `exceptions` package | `com.cae.clean_env_vars` and its `exceptions` package |
| Java 11 | Java 25 LTS |
| `getEnvVarByName(name, Integer.class)` attempts a string cast | Parses the supplied text as an integer |
| Unrecognized boolean text becomes `false` | Throws `InvalidEnvVarException` |
| Only required process-environment lookups | Required, optional, and defaulted lookups through configurable providers |
| CAE Trier wraps retrieval failures | Direct dependency on Clean Mapped Exceptions |
| `UnexpectedException` diagnostics combine raw exception text | Distinct invalid/access subtypes with original Java causes |

The four static method names, `MissingEnvVarException`, and `UnexpectedException` are retained under the new package root. `InvalidEnvVarException` and `EnvVarAccessException` extend `UnexpectedException`, allowing existing catch patterns to be migrated by changing their imports.

Replace dependency coordinates and imports, review the stricter conversion rules, and recompile consumers. This is a separate artifact rather than a binary-compatible replacement.

## Verification and automation

`mvn clean verify` enables `-Xlint:all,-serial`, fails on compiler and Javadoc warnings, runs unit tests, and enforces zero missed instructions, branches, lines, methods, and complexity for each production class without exclusions. Tests use custom in-memory providers and controlled process-environment fixtures rather than modifying JVM internals.

Reports are written to `target/surefire-reports/` and `target/site/jacoco/`. For SonarQube, set `SONAR_HOST_URL` and `SONAR_TOKEN`, then run `mvn clean verify sonar:sonar`.

Repository workflows cover [feature pull requests](../../.github/FEATURE_PRS.md), Java verification on PRs to `main`, and [Maven Central publishing](../../.github/PUBLISHING.md). The publishing guide lists the required repository secrets and activation settings.
