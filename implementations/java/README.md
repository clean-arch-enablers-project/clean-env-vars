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

## Static lookups

All lookups use the shared provider chain. It starts with the process environment:

```java
import com.cae.clean_env_vars.EnvVarRetriever;

String host = EnvVarRetriever.getEnvVarByNameAsString("APP_HOST");
Integer port = EnvVarRetriever.getEnvVarByNameAsInteger("APP_PORT");
Boolean enabled = EnvVarRetriever.getEnvVarByNameAsBoolean("APP_ENABLED");
Integer workers = EnvVarRetriever.getEnvVarByName("APP_WORKERS", Integer.class);
```

The four `getEnvVarByName*` methods require a value to be present. The generic method converts the provider's text to the requested class.

The same static API supports required, optional, and defaulted requests:

```java
String host = EnvVarRetriever.getRequired("APP_HOST", String.class);
var optionalLabel = EnvVarRetriever.getOptional("APP_LABEL", String.class);
Integer port = EnvVarRetriever.getOrDefault("APP_PORT", Integer.class, 8080);
```

Each request performs a separate lookup and reads its providers' current values.

## Configuring the provider chain

An `EnvVarProvider` returns a non-null `Optional<String>` for a name and may throw an unchecked exception if its source is inaccessible. `Optional.empty()` means absent; `Optional.of("")` means found and empty.

```java
import com.cae.clean_env_vars.EnvVarProvider;
import com.cae.clean_env_vars.EnvVarProviderChain;
import com.cae.clean_env_vars.EnvVarRetriever;
import java.util.Map;
import java.util.Optional;

Map<String, String> applicationValues = Map.of("APP_PORT", "8080");
EnvVarProvider applicationProvider = name -> Optional.ofNullable(applicationValues.get(name));

var chain = EnvVarProviderChain.SINGLETON;
chain.add(applicationProvider);
chain.lock();

Integer port = EnvVarRetriever.getEnvVarByNameAsInteger("APP_PORT");
```

The built-in `SystemEnvironmentProvider` is permanent and runs first. Custom providers are consulted in registration order. The first found value stops lookup before conversion, including empty or invalid text.

While the chain is unlocked, `add` appends a custom provider and `remove` unregisters it. Both return whether they changed the chain. Equal providers are registered once, and the process environment provider has a single entry. CleanSetup can call `lock()` after registration to permanently freeze the chain. Passing a null provider or trying to change a locked chain is an application setup error and throws `InternalMappedException`. Each lookup uses a snapshot of the registered providers, so registration changes take effect on subsequent lookups. Provider implementations remain responsible for their own concurrency and source lifecycle.

Custom providers can use an application's configuration-service clients. If a client throws checked exceptions, the provider handles them or wraps them in an unchecked exception that preserves the cause, such as `UncheckedIOException` for an `IOException`. The library does not require an AWS client, framework, or dependency injection container.

### Access failures

If a provider throws, lookup continues with later providers. If none supplies a value, the retriever throws `EnvVarAccessException` with the first provider exception as its cause and adds any later provider exceptions as suppressed exceptions. This applies to required, optional, and defaulted requests.

A null provider result counts as a provider failure. Wrap an `InterruptedException` as the direct cause of an unchecked exception so the retriever restores the thread's interrupted flag and stops lookup. JVM `Error` instances propagate directly.

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

These failures directly extend Clean `InternalMappedException`, matching configuration-resolution failures during application assembly. Application workflows can map them to another category when their context warrants it. Invalid lookup requests, such as null arguments or unsupported class tokens, throw standard argument exceptions.

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
| `UnexpectedException` diagnostics combine raw exception text | `InvalidEnvVarException` and `EnvVarAccessException` preserve the original Java cause |

The four static method names and `MissingEnvVarException` are retained under the new package root. Replace existing `UnexpectedException` catch blocks with catches for `InvalidEnvVarException` and `EnvVarAccessException`. A multi-catch can handle both together.

Replace dependency coordinates and imports, review the stricter conversion rules, and recompile consumers. This is a separate artifact rather than a binary-compatible replacement.

## Verification and automation

`mvn clean verify` enables `-Xlint:all,-serial`, fails on compiler and Javadoc warnings, runs unit tests, and enforces zero missed instructions, branches, lines, methods, and complexity for each production class without exclusions. Tests use custom in-memory providers and controlled process-environment fixtures rather than modifying JVM internals.

Reports are written to `target/surefire-reports/` and `target/site/jacoco/`. For SonarQube, set `SONAR_HOST_URL` and `SONAR_TOKEN`, then run `mvn clean verify sonar:sonar`.

Repository workflows cover [feature pull requests](../../.github/FEATURE_PRS.md), Java verification on PRs to `main`, and [Maven Central publishing](../../.github/PUBLISHING.md). The publishing guide lists the required repository secrets and activation settings.
