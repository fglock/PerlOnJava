# Port CPAN Module to PerlOnJava

## ⚠️⚠️⚠️ CRITICAL: NEVER USE `git stash` ⚠️⚠️⚠️

**DANGER: Changes are SILENTLY LOST when using git stash/stash pop!**

- NEVER use `git stash` to temporarily revert changes
- INSTEAD: Commit to a WIP branch or use `git diff > backup.patch`
- This warning exists because completed work was lost during debugging

## ⚠️⚠️⚠️ CRITICAL: NEVER MODIFY OR DELETE TESTS ⚠️⚠️⚠️

**Tests are the source of truth. If a test fails, fix the code, not the test.**

- NEVER remove a test file because it fails — fix the underlying bug instead
- NEVER edit a test to make it pass — the test defines correct behavior
- If a test cannot pass yet due to a known limitation, leave it in place and document the issue
- This applies to ALL tests: unit tests, bundled module tests, and upstream CPAN tests

This skill guides you through porting a CPAN module with XS/C components to PerlOnJava using Java implementations.

**Authoritative reference:** `docs/guides/module-porting.md` — always defer to that document for
naming conventions, directory layout, and checklists. This skill provides step-by-step
guidance for the AI agent; the guide is the source of truth for contributors.

## When to Use This Skill

- User asks to add a CPAN module to PerlOnJava
- User asks to port a Perl module with XS code
- User wants to implement Perl module functionality in Java

## Key Principles

1. **Reuse as much original code as possible** - Most CPAN modules are 70-90% pure Perl. Only the XS/C portions need Java replacements. Copy the original `.pm` code and adapt minimally.

2. **Always inspect the XS source** - The `.xs` file reveals exactly what needs Java implementation. Study it to understand the C algorithms, edge cases, and expected behavior.

3. **Credit original authors** - Always preserve the original AUTHORS and COPYRIGHT sections in the POD. Add a note that this is a PerlOnJava port.

## Overview

PerlOnJava supports three types of modules:
1. **Pure Perl modules** - Work directly, no Java needed
2. **Java-implemented modules (XSLoader)** - Replace XS/C with Java
3. **Built-in modules (GlobalContext)** - Internal only

**Most CPAN ports use type #2 (XSLoader).**

## Step-by-Step Process

### Phase 1: Analysis

1. **Fetch the original module source:**
   ```
   https://fastapi.metacpan.org/v1/source/AUTHOR/Module-Version/Module.pm
   https://fastapi.metacpan.org/v1/source/AUTHOR/Module-Version/Module.xs
   ```

2. **Study the XS file thoroughly:**
   - Look for `MODULE = ` and `PACKAGE = ` declarations
   - Identify each XS function (appears after `void` or return type)
   - Read the C code to understand algorithms and edge cases
   - Note any platform-specific code (WIN32, etc.)
   - Check for copyright notices to preserve

3. **Identify what needs Java implementation:**
   - Functions defined in `.xs` files
   - Functions that call C libraries (strftime, crypt, etc.)
   - Functions loaded via `XSLoader::load()`

4. **Identify what can be reused as pure Perl (typically 70-90%):**
   - Most accessor methods
   - Helper/utility functions  
   - Overloaded operators
   - Import/export logic
   - Format translation maps
   - Constants and configuration

5. **Check for dependencies:**
   - Other modules the target depends on
   - Whether those dependencies exist in PerlOnJava

6. **Check available Java libraries:**
   - Review `build.gradle` for already-imported dependencies
   - Common libraries already available: Gson, jnr-posix, jnr-ffi, SnakeYAML, etc.
   - Consider if a Java library can replace the XS functionality directly

7. **Check existing PerlOnJava infrastructure:**
   - `org.perlonjava.runtime.nativ.PosixLibrary` - JNR-POSIX wrapper for native calls
   - `org.perlonjava.runtime.nativ.NativeUtils` - Cross-platform utilities with Windows fallbacks
   - `org.perlonjava.runtime.operators.*` - Existing operator implementations

### Phase 2: Create Java Implementation

**File location:** `src/main/java/org/perlonjava/runtime/perlmodule/`

**Naming convention:** `Module::Name` → `ModuleName.java`
- `Time::Piece` → `TimePiece.java`
- `Digest::MD5` → `DigestMD5.java`
- `DBI` → `DBI.java` (all-caps modules keep their casing)

**Basic structure:**
```java
package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

public class ModuleName extends PerlModuleBase {

    public ModuleName() {
        super("Module::Name", false);  // false = not a pragma
    }

    public static void initialize() {
        ModuleName module = new ModuleName();
        try {
            // Register methods - Perl name, Java method name (null = same), prototype
            module.registerMethod("xs_function", null);
            module.registerMethod("perl_name", "javaMethodName", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing method: " + e.getMessage());
        }
    }

    // Method signature: (RuntimeArray args, int ctx) -> RuntimeList
    public static RuntimeList xs_function(RuntimeArray args, int ctx) {
        // args.get(0) = first argument ($self for methods)
        // ctx = RuntimeContextType.SCALAR, LIST, or VOID
        
        String param = args.get(0).toString();
        int number = args.get(1).getInt();
        
        // Return value
        return new RuntimeScalar(result).getList();
    }
}
```

### Phase 3: Create Perl Wrapper

**File location:** `src/main/perl/lib/Module/Name.pm`

**Template:**
```perl
package Module::Name;

use strict;
use warnings;

our $VERSION = '1.00';

# Load Java implementation
use XSLoader;
XSLoader::load('Module::Name', $VERSION);

# Pure Perl code from original module goes here
# (accessors, helpers, overloads, etc.)

1;

__END__

=head1 NAME

Module::Name - Description

=head1 DESCRIPTION

This is a port of the CPAN Module::Name module for PerlOnJava.

=head1 AUTHOR

Original Author Name, original@email.com

Additional Author, other@email.com (if applicable)

=head1 COPYRIGHT AND LICENSE

Copyright YEAR, Original Copyright Holder.

This module is free software; you may distribute it under the same terms
as Perl itself.

=cut
```

### Phase 4: Testing

**ALWAYS use `make` commands. NEVER use raw mvn/gradlew commands.**

| Command | What it does |
|---------|--------------|
| `make` | Build + run all unit tests (use before committing) |
| `make dev` | Build only, skip tests (for quick iteration during development) |
| `make test-bundled-modules` | Run bundled CPAN module tests (XML::Parser, etc.) |

1. **Create module test directory:** `src/test/resources/module/Module-Name/t/`
   - Add `.t` test files inside `t/`
   - Add supporting data as sibling directories (`samples/`, `files/`, etc.)
   - `ModuleTestExecutionTest.java` auto-discovers all `.t` files under `module/*/t/`
   - Each test runs with `chdir` set to the module's root (e.g., `module/Module-Name/`)
   - Use `JPERL_TEST_FILTER=Module-Name` to run only matching tests

   Every bundled module MUST have tests in `src/test/resources/module/Module-Name/t/`.
   This is how CI verifies the module keeps working across changes.

   **How to populate the test directory:**
   - Download the upstream CPAN distribution (e.g. from MetaCPAN or via `./jcpan`)
   - Copy the `t/` directory from the distribution into `src/test/resources/module/Module-Name/t/`
   - Also copy any support files the tests need (sample data, test certificates, etc.)
   - All included tests MUST pass: `make test-bundled-modules`

   ```
   src/test/resources/module/
   ├── XML-Parser/          # existing example
   │   └── t/
   │       ├── cdata.t
   │       ├── encoding.t
   │       └── samples/
   └── Module-Name/         # your new module
       └── t/
           ├── basic.t
           ├── feature.t
           └── samples/
               └── test-data.txt
   ```

   These tests are run by `make test-bundled-modules`, NOT by `make` (which runs unit tests only).

2. **Test with `jcpan` if the module is on CPAN:**

   If the module has an upstream CPAN distribution with its own test suite,
   run it to verify compatibility:
   ```bash
   ./jcpan -t Module::Name
   ```
   This downloads the CPAN distribution, installs it, and runs the upstream tests.

3. **Compare with system Perl:**
   ```bash
   # Create test script
   cat > /tmp/test.pl << 'EOF'
   use Module::Name;
   # test code
   EOF
   
   # Run with both
   perl /tmp/test.pl
   ./jperl /tmp/test.pl
   ```

3. **Install/test modules with `jcpan`:**

   Use `./jcpan` to install and test CPAN modules:
   ```bash
   ./jcpan Module::Name            # Install a module
   ./jcpan -t Module::Name         # Test a module (download + install + run upstream tests)
   ```
   `jcpan` installs modules into the `.perlonjava/` directory in the project root.

4. **Build and verify:**
   ```bash
   make dev                    # Quick build (no tests)
   ./jperl -e 'use Module::Name; ...'
   make test-bundled-modules   # Module-specific tests
   make                        # Full build with all tests before committing
   ```

5. **Cleanup `.perlonjava/` after bundling:**

   When all tests pass and the module is bundled into the project (i.e. its `.pm` and
   `.java` files are in `src/main/perl/lib/` and `src/main/java/`), remove the
   `.perlonjava/` directory so the bundled version is used instead of the jcpan-installed copy:
   ```bash
   rm -rf .perlonjava/
   ```
   Then verify the bundled version and all its dependencies load correctly:
   ```bash
   ./jperl -e 'use Module::Name; print "ok\n"'
   ```
   If this fails with a "Can't locate Dependency/Module.pm" error, the dependency
   is not bundled. You must bundle all dependencies too — bundled modules must be
   fully self-contained with no CPAN installs required.

### Importing Core Perl Modules

If the module's `.pm` files come from the Perl 5 source tree (core modules),
use `dev/import-perl5/sync.pl` instead of copying them manually:

1. Add entries to `dev/import-perl5/config.yaml` (source/target pairs)
2. Run `perl dev/import-perl5/sync.pl`
3. If the module needs PerlOnJava-specific changes, mark it `protected: true`
   and optionally provide a patch in `dev/import-perl5/patches/`

See `docs/guides/module-porting.md` for full details on the sync workflow.

## Common Patterns

### Reading XS Files

XS files have a specific structure:

```c
MODULE = Time::Piece     PACKAGE = Time::Piece

void
_strftime(fmt, epoch, islocal = 1)
    char * fmt
    time_t epoch
    int islocal
CODE:
    /* C implementation here */
    ST(0) = sv_2mortal(newSVpv(result, len));
```

Key elements to identify:
- **Function name**: `_strftime` (usually prefixed with `_` for internal XS)
- **Parameters**: `fmt`, `epoch`, `islocal` with their C types
- **Default values**: `islocal = 1`
- **Return mechanism**: `ST(0)`, `RETVAL`, or stack manipulation

### Converting XS to Java

| XS Pattern | Java Equivalent |
|------------|-----------------|
| `SvIV(arg)` | `args.get(i).getInt()` |
| `SvNV(arg)` | `args.get(i).getDouble()` |
| `SvPV(arg, len)` | `args.get(i).toString()` |
| `newSViv(n)` | `new RuntimeScalar(n)` |
| `newSVnv(n)` | `new RuntimeScalar(n)` |
| `newSVpv(s, len)` | `new RuntimeScalar(s)` |
| `av_fetch(av, i, 0)` | `array.get(i)` |
| `hv_fetch(hv, k, len, 0)` | `hash.get(k)` |
| `RETVAL` / `ST(0)` | `return new RuntimeScalar(x).getList()` |

### Using Existing Java Libraries

**Check `build.gradle` for available dependencies:**
```bash
grep "implementation" build.gradle
```

**Common libraries already in PerlOnJava:**

| Java Library | Use Case | Example Module |
|--------------|----------|----------------|
| Gson | JSON parsing/encoding | `Json.java` |
| jnr-posix | Native POSIX calls | `POSIX.java` |
| jnr-ffi | Foreign function interface | Native bindings |
| SnakeYAML | YAML parsing | `YAMLPP.java` |
| TOML4J | TOML parsing | `Toml.java` |
| Java stdlib | Crypto, encoding, time | Various |

**Example: JSON.java uses Gson directly:**
```java
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public static RuntimeList encode_json(RuntimeArray args, int ctx) {
    Gson gson = new GsonBuilder().create();
    String json = gson.toJson(convertToJava(args.get(0)));
    return new RuntimeScalar(json).getList();
}
```

**Standard Java imports:**
```java
// Time operations
import java.time.*;
import java.time.format.DateTimeFormatter;

// Crypto
import java.security.MessageDigest;

// Encoding
import java.util.Base64;
import java.nio.charset.StandardCharsets;

// Native POSIX calls (with Windows fallbacks)
import org.perlonjava.runtime.nativ.PosixLibrary;
import org.perlonjava.runtime.nativ.NativeUtils;
```

**Using PosixLibrary for native calls:**
```java
// Direct POSIX call (Unix only)
int uid = PosixLibrary.INSTANCE.getuid();

// Cross-platform with Windows fallback (preferred)
RuntimeScalar uid = NativeUtils.getuid(ctx);
```

### Returning Different Types

```java
// Scalar
return new RuntimeScalar(value).getList();

// List
RuntimeList result = new RuntimeList();
result.add(new RuntimeScalar(item1));
result.add(new RuntimeScalar(item2));
return result;

// Array reference
RuntimeArray arr = new RuntimeArray();
arr.push(new RuntimeScalar(item));
return arr.createReference().getList();

// Hash reference
RuntimeHash hash = new RuntimeHash();
hash.put("key", new RuntimeScalar(value));
return hash.createReference().getList();
```

### Handling Context

```java
public static RuntimeList myMethod(RuntimeArray args, int ctx) {
    if (ctx == RuntimeContextType.SCALAR) {
        // Return single value
        return new RuntimeScalar(count).getList();
    } else {
        // Return list
        RuntimeList result = new RuntimeList();
        for (String item : items) {
            result.add(new RuntimeScalar(item));
        }
        return result;
    }
}
```

## Checklist

### Pre-porting
- [ ] Fetch original `.pm` and `.xs` source
- [ ] Study XS code to understand C algorithms and edge cases
- [ ] Identify XS functions that need Java implementation
- [ ] Check dependencies exist in PerlOnJava
- [ ] Check `build.gradle` for usable Java libraries
- [ ] Check `nativ/` package for POSIX functionality
- [ ] Review existing similar modules for patterns

### Implementation
- [ ] Create `ModuleName.java` with XS replacements
- [ ] Create `Module/Name.pm` with pure Perl code
- [ ] Add proper author/copyright attribution
- [ ] Register all methods in `initialize()`
- [ ] Create `src/test/resources/module/Module-Name/t/` with test files

### Testing
- [ ] Build compiles without errors: `make dev` (NEVER use raw mvn/gradlew)
- [ ] Basic functionality works: `./jperl -e 'use Module::Name; ...'`
- [ ] Compare output with system Perl
- [ ] Test edge cases identified in XS code
- [ ] Copy upstream CPAN tests into `src/test/resources/module/Module-Name/t/`
- [ ] Run bundled module tests: `make test-bundled-modules`
- [ ] Run upstream CPAN tests if applicable: `./jcpan -t Module::Name`

### Cleanup
- [ ] Check all dependencies are also bundled: `./jperl -e 'use Module::Name'` should not pull anything from `.perlonjava/`
- [ ] Remove `.perlonjava/` directory so the bundled version is used: `rm -rf .perlonjava/`
- [ ] Verify bundled version loads without `.perlonjava/`: `./jperl -e 'use Module::Name; print "ok\n"'`
- [ ] If it fails, identify the missing dependency and bundle it too (bundled modules must be fully self-contained)

### Documentation
- [ ] Add POD with AUTHOR and COPYRIGHT sections
- [ ] Credit original authors
- [ ] Update `docs/about/changelog.md` — add module to "Add modules:" list in the current unreleased version
- [ ] Update `docs/reference/feature-matrix.md` — add entry in the appropriate section (Core modules / Non-core modules) with status icon and description
- [ ] Update `README.md` if the module is notable enough to mention in the Features list

## Example: Time::Piece Port

**Files created:**
- `src/main/java/org/perlonjava/runtime/perlmodule/TimePiece.java`
- `src/main/java/org/perlonjava/runtime/perlmodule/POSIX.java` (for strftime)
- `src/main/perl/lib/Time/Piece.pm`
- `src/main/perl/lib/Time/Seconds.pm`

**XS functions replaced:**
| XS Function | Java Implementation |
|-------------|---------------------|
| `_strftime(fmt, epoch, islocal)` | `DateTimeFormatter` with format mapping |
| `_strptime(str, fmt, gmt, locale)` | `DateTimeFormatter.parse()` |
| `_tzset()` | No-op (Java handles TZ) |
| `_crt_localtime(epoch)` | `ZonedDateTime` conversion |
| `_crt_gmtime(epoch)` | `ZonedDateTime` at UTC |
| `_get_localization()` | `DateFormatSymbols` |
| `_mini_mktime(...)` | `LocalDateTime` normalization |

**Pure Perl reused (~80%):**
- All accessor methods (sec, min, hour, year, etc.)
- Formatting helpers (ymd, hms, datetime)
- Julian day calculations
- Overloaded operators
- Import/export logic

## Troubleshooting

### "Can't load Java XS module"
- Check class name matches: `Module::Name` → `ModuleName.java`
- Verify `initialize()` method exists and is static
- Check package is `org.perlonjava.runtime.perlmodule`

### Method not found
- Ensure method is registered in `initialize()`
- Check method signature: `public static RuntimeList name(RuntimeArray args, int ctx)`

### Different output than system Perl
- Compare with fixed test values (not current time)
- Check locale handling
- Verify edge cases from XS comments

## References

- **Module porting guide (authoritative):** `docs/guides/module-porting.md`
- **Dual-backend design doc:** `dev/design/DUAL_BACKEND_CPAN_MODULES.md` — for Option B (CPAN modules with Java XS)
- **Core module import tool:** `dev/import-perl5/sync.pl` + `dev/import-perl5/config.yaml`
- **Module test runner:** `src/test/java/org/perlonjava/ModuleTestExecutionTest.java`
- Existing modules: `src/main/java/org/perlonjava/runtime/perlmodule/`
- Runtime types: `src/main/java/org/perlonjava/runtime/runtimetypes/`
