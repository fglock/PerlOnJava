# Porting Perl Modules to PerlOnJava

## Overview

There are two ways to provide Java XS support for a Perl module:

1. **Option A: Bundle into PerlOnJava** — The Java class ships inside the PerlOnJava JAR.
   Best for core infrastructure modules (DateTime, Digest::MD5, DBI, etc.) maintained by the PerlOnJava project.

2. **Option B: Publish a dual-backend CPAN module** — The `.java` file ships inside the CPAN distribution alongside the `.pm` files. `jcpan` compiles it at install time.
   Best for third-party module authors who want their module to work on both `perl` and `jperl`.

Both options use the same XSLoader mechanism at runtime. The only difference is **where the Java class lives** and **who compiles it**.

Pure Perl modules require no porting — they work as-is on PerlOnJava.

---

## Option A: Bundle a Module into PerlOnJava

Use this when adding Java XS support to a module that the PerlOnJava project maintains.

### Directory Layout

```
src/main/
├── perl/lib/
│   └── Module/
│       └── Name.pm              # Perl wrapper (calls XSLoader::load)
└── java/org/perlonjava/runtime/perlmodule/
    └── ModuleName.java          # Java XS implementation

src/test/resources/module/
└── Module-Name/
    ├── t/                       # .t test files (run by ModuleTestExecutionTest)
    ├── samples/                 # test data files (optional)
    └── lib/                     # test-specific libraries (optional)
```

### Importing Core Perl Modules with sync.pl

Core Perl modules (the pure Perl `.pm` files) are imported from the Perl 5 source
tree using `dev/import-perl5/sync.pl`. This script reads `dev/import-perl5/config.yaml`
and copies files from the `perl5/` checkout into the PerlOnJava tree:

- **Perl modules** → `src/main/perl/lib/` (shipped inside the PerlOnJava JAR)
- **Module tests** → `perl5_t/` (external test suite, not in git)

To add a new core module import:

1. Add entries to `dev/import-perl5/config.yaml` (source/target pairs)
2. Run `perl dev/import-perl5/sync.pl`
3. If the module needs PerlOnJava-specific changes, mark it as `protected: true`
   and optionally provide a patch file in `dev/import-perl5/patches/`

> **TODO:** `sync.pl` should be updated to copy core module tests into
> `src/test/resources/module/` instead of `perl5_t/`, so they are picked up by
> `ModuleTestExecutionTest` and run as part of `make test-bundled-modules`.

### Naming Convention

XSLoader maps Perl module names to Java class names:

| Perl Module | Java Class | Java File |
|---|---|---|
| `DBI` | `org.perlonjava.runtime.perlmodule.DBI` | `DBI.java` |
| `Text::CSV` | `org.perlonjava.runtime.perlmodule.TextCsv` | `TextCsv.java` |
| `Time::HiRes` | `org.perlonjava.runtime.perlmodule.TimeHiRes` | `TimeHiRes.java` |
| `MIME::Base64` | `org.perlonjava.runtime.perlmodule.MIMEBase64` | `MIMEBase64.java` |
| `B::Hooks::EndOfScope` | `org.perlonjava.runtime.perlmodule.BHooksEndOfScope` | `BHooksEndOfScope.java` |

Rules:
- Package: always `org.perlonjava.runtime.perlmodule`
- Class name: `::` separators removed, CamelCased
- The constructor passes the **original Perl module name** to `super()`

### Java Implementation

```java
package org.perlonjava.runtime.perlmodule;

import org.perlonjava.runtime.runtimetypes.*;

public class ModuleName extends PerlModuleBase {

    public ModuleName() {
        super("Module::Name", false);  // false = not a pragma
    }

    // Called by XSLoader::load('Module::Name')
    public static void initialize() {
        ModuleName module = new ModuleName();
        try {
            module.registerMethod("xs_function", null);
            module.registerMethod("perl_name", "javaMethodName", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing method: " + e.getMessage());
        }
    }

    // Method signature: (RuntimeArray args, int ctx) -> RuntimeList
    public static RuntimeList xs_function(RuntimeArray args, int ctx) {
        String param = args.get(0).toString();
        return new RuntimeScalar(result).getList();
    }
}
```

### Perl Wrapper

```perl
package Module::Name;
use strict;
use warnings;

our $VERSION = '1.00';

# Load Java implementation
use XSLoader;
XSLoader::load('Module::Name', $VERSION);

# Pure Perl methods can coexist with Java methods
sub helper_method {
    my ($self, @args) = @_;
    return $self->java_implemented_method(@args);
}

1;
```

### Module Registration

There are two sub-types for bundled modules:

**XSLoader modules (standard)** — Loaded on demand when the Perl `.pm` file calls `XSLoader::load()`. This is the right choice for almost all modules.

**Built-in modules (GlobalContext)** — Only for internal PerlOnJava modules that must be available at startup (UNIVERSAL, CORE functions). Registered in `GlobalContext.java`:

```java
DiamondIO.initialize(compilerOptions);
Universal.initialize();
```

Do not use GlobalContext for CPAN-style modules.

### How XSLoader Resolution Works

When `XSLoader::load('Module::Name')` is called:
1. XSLoader looks for the Java class `org.perlonjava.runtime.perlmodule.ModuleName` in the JAR
2. Calls the static `initialize()` method
3. Methods are registered into the Perl namespace

This is transparent to users — they just `use Module::Name` and it works.

### Build and Test

```bash
make dev    # Quick build (no tests) — for iteration
make        # Full build + all unit tests — before committing
./jperl -e 'use Module::Name; ...'   # Quick smoke test
```

### Module Test Directory

Bundled module tests live under `src/test/resources/module/` in a CPAN-like layout:

```
src/test/resources/module/
├── Text-CSV/
│   ├── lib/            # module-specific test libraries
│   ├── files/          # test data files
│   └── t/              # .t test files
└── XML-Parser/
    ├── samples/        # sample data files
    └── t/              # .t test files
```

The `ModuleTestExecutionTest.java` test runner automatically discovers all `.t`
files under `module/*/t/` and executes them. Key behaviors:

- **Working directory** — Each test runs with `chdir` set to the module's root
  directory (e.g., `module/XML-Parser/`), so relative paths like `samples/foo.xml`
  resolve correctly.
- **TAP validation** — Output is checked for `not ok` (excluding `# TODO`) and
  `Bail out!` lines.
- **Filtering** — Set `JPERL_TEST_FILTER=Text-CSV` to run only matching tests.
- **JUnit tag** — Module tests are tagged `@Tag("module")` so they can be run
  separately with `make test-bundled-modules`.

To add tests for a new bundled module:

1. Create `src/test/resources/module/Module-Name/t/` with `.t` files
2. Add any supporting data files as sibling directories (`samples/`, `files/`, etc.)
3. Run `make test-bundled-modules` to verify

### Bundled Module Checklist

- [ ] Fetch original `.pm` and `.xs` source from CPAN
- [ ] Study XS code to understand C algorithms and edge cases
- [ ] Check `build.gradle` for usable Java libraries
- [ ] Create `ModuleName.java` in `src/main/java/org/perlonjava/runtime/perlmodule/`
- [ ] Create `Module/Name.pm` in `src/main/perl/lib/`
- [ ] Preserve original author/copyright attribution
- [ ] Register all methods in `initialize()`
- [ ] Create `src/test/resources/module/Module-Name/t/` with test files
- [ ] `make dev` compiles without errors
- [ ] Compare output with system Perl
- [ ] `make` passes all unit tests
- [ ] `make test-bundled-modules` passes module-specific tests

---

## Option B: Publish a Dual-Backend CPAN Module

> **⚠️ Status: Not yet implemented.** This section describes the planned design for
> dual-backend CPAN modules. See the [design document](../../dev/design/DUAL_BACKEND_CPAN_MODULES.md)
> for implementation plan and progress tracking.

Use this when you are a CPAN module author and want your module to work on both standard Perl (`perl`) and PerlOnJava (`jperl`).

### How It Works

The module ships with:
- `.pm` files (work on both backends)
- `.xs` file (compiled by standard Perl's `make`)
- `.java` file (compiled by `jcpan` at install time)

On **standard Perl**: `ExtUtils::MakeMaker` compiles the `.xs` as usual.
On **PerlOnJava**: `jcpan` ignores the `.xs`, compiles the `.java`, and installs the resulting JAR.

### Distribution Layout

```
Foo-Bar-1.00/
├── lib/
│   └── Foo/
│       ├── Bar.pm           # Main module — calls XSLoader::load()
│       └── Bar/
│           └── PP.pm        # Pure Perl fallback (optional but recommended)
├── java/
│   └── Foo/
│       └── Bar.java         # Java XS implementation for PerlOnJava
├── Bar.xs                   # C XS implementation for standard Perl
├── Makefile.PL
├── t/
│   └── basic.t
└── META.json
```

The `java/` directory mirrors the `lib/` structure using the Perl module path, **not** Java package conventions. This keeps it simple for Perl authors who may not know Java packaging.

### The Perl Module (.pm)

The `.pm` file uses the standard XSLoader fallback pattern that works on both backends:

```perl
package Foo::Bar;
use strict;
use warnings;

our $VERSION = '1.00';
our $IsPurePerl;

eval {
    require XSLoader;
    XSLoader::load('Foo::Bar', $VERSION);
    $IsPurePerl = 0;
};
if ($@) {
    require Foo::Bar::PP;    # Pure Perl fallback
    $IsPurePerl = 1;
}

1;
```

On standard Perl, `XSLoader` loads the compiled `.so` from `auto/`.
On PerlOnJava, `XSLoader` loads the compiled `.jar` from `auto/`.
If neither is available, the PP fallback kicks in.

### The Java Implementation (.java)

```java
package org.perlonjava.cpan.foo;

import org.perlonjava.runtime.perlmodule.PerlModuleBase;
import org.perlonjava.runtime.runtimetypes.*;

public class Bar extends PerlModuleBase {

    public Bar() {
        super("Foo::Bar", false);
    }

    public static void initialize() {
        Bar module = new Bar();
        try {
            module.registerMethod("fast_function", null);
        } catch (NoSuchMethodException e) {
            System.err.println("Warning: Missing method: " + e.getMessage());
        }
    }

    public static RuntimeList fast_function(RuntimeArray args, int ctx) {
        String input = args.get(0).toString();
        // Java implementation replacing the C XS code
        return new RuntimeScalar(result).getList();
    }
}
```

### The Java File Manifest

Include a `META-INF/perlonjava.properties` inside the distribution's `java/` directory so `jcpan` knows how to compile and register the module:

```properties
# java/META-INF/perlonjava.properties
perl-module=Foo::Bar
main-class=org.perlonjava.cpan.foo.Bar
```

### Where jcpan Installs It

`jcpan` mirrors Perl's `auto/` convention for compiled XS:

```
~/.perlonjava/
├── lib/                              # .pm files
│   └── Foo/
│       ├── Bar.pm
│       └── Bar/
│           └── PP.pm
└── auto/                             # compiled Java XS
    └── Foo/
        └── Bar/
            ├── Bar.jar               # compiled module JAR
            └── Bar.java              # source (kept for recompilation)
```

### What jcpan Does at Install Time

1. Copies `.pm` files to `~/.perlonjava/lib/` (standard behavior)
2. Detects the `java/` directory in the distribution
3. Compiles the `.java` file against `perlonjava.jar`:
   ```bash
   javac -cp perlonjava.jar -d /tmp/build java/Foo/Bar.java
   jar cf ~/.perlonjava/auto/Foo/Bar/Bar.jar -C /tmp/build .
   ```
4. Copies the source to `~/.perlonjava/auto/Foo/Bar/Bar.java`

### XSLoader Search Order

When `XSLoader::load('Foo::Bar')` is called at runtime:

1. **Built-in registry** — Java classes in the PerlOnJava JAR (`org.perlonjava.runtime.perlmodule.*`)
2. **`auto/` JARs** — `~/.perlonjava/auto/Foo/Bar/Bar.jar` (CPAN-installed)
3. **Fail** — dies with `"Can't load loadable object for module Foo::Bar"`, which triggers the PP fallback if the module has one

### Makefile.PL for Dual Backend

```perl
use ExtUtils::MakeMaker;

WriteMakefile(
    NAME         => 'Foo::Bar',
    VERSION_FROM => 'lib/Foo/Bar.pm',
    XS           => { 'Bar.xs' => 'Bar.c' },   # standard Perl XS
    # jcpan ignores XS and uses java/ directory instead
);
```

No changes to `Makefile.PL` are needed — `jcpan` handles the `java/` directory automatically.

### Dual-Backend Module Checklist

- [ ] Module works on standard Perl with `.xs` (existing behavior)
- [ ] Add `java/` directory with Java XS implementation
- [ ] Add `java/META-INF/perlonjava.properties` manifest
- [ ] `.pm` file has XSLoader fallback pattern (eval + PP require)
- [ ] Test with `jcpan install ./` from the distribution directory
- [ ] Test with standard `perl Makefile.PL && make test`
- [ ] Both backends produce the same output
- [ ] Credit PerlOnJava port in documentation

---

## Java Implementation Reference

### Calling Conventions

All Java XS methods have the same signature:

```java
public static RuntimeList method_name(RuntimeArray args, int ctx)
```

- `args.get(0)` — first argument (`$self` for methods)
- `ctx` — `RuntimeContextType.SCALAR`, `LIST`, or `VOID`

### Returning Values

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

### Defining Exports

```java
module.defineExport("EXPORT", "function1", "function2");
module.defineExport("EXPORT_OK", "optional_function");
module.defineExportTag("group", "function1", "function2");
```

### Converting XS Patterns to Java

| XS Pattern | Java Equivalent |
|---|---|
| `SvIV(arg)` | `args.get(i).getInt()` |
| `SvNV(arg)` | `args.get(i).getDouble()` |
| `SvPV(arg, len)` | `args.get(i).toString()` |
| `newSViv(n)` | `new RuntimeScalar(n)` |
| `newSVnv(n)` | `new RuntimeScalar(n)` |
| `newSVpv(s, len)` | `new RuntimeScalar(s)` |
| `av_fetch(av, i, 0)` | `array.get(i)` |
| `hv_fetch(hv, k, len, 0)` | `hash.get(k)` |
| `RETVAL` / `ST(0)` | `return new RuntimeScalar(x).getList()` |

### Available Java Libraries

Check `build.gradle` for dependencies already in PerlOnJava:

| Java Library | Use Case | Example Module |
|---|---|---|
| Gson | JSON parsing/encoding | `Json.java` |
| jnr-posix | Native POSIX calls | `POSIX.java` |
| jnr-ffi | Foreign function interface | Native bindings |
| SnakeYAML | YAML parsing | `YAMLPP.java` |
| java.time | Date/time operations | `DateTime.java` |
| java.security | Crypto (MD5, SHA) | `DigestMD5.java` |
| java.util.Base64 | Base64 encoding | `MIMEBase64.java` |

### Using PosixLibrary for Native Calls

```java
// Direct POSIX call (Unix only)
int uid = PosixLibrary.INSTANCE.getuid();

// Cross-platform with Windows fallback (preferred)
RuntimeScalar uid = NativeUtils.getuid(ctx);
```

---

## Real-World Examples

### Bundled: DateTime (Option A)

The DateTime module provides Java XS using `java.time` APIs:

| XS Function | Java Implementation |
|---|---|
| `_rd2ymd(rd)` | `LocalDate.MIN.with(JulianFields.RATA_DIE, rd)` |
| `_ymd2rd(y, m, d)` | `LocalDate.of(y, m, d).getLong(JulianFields.RATA_DIE)` |
| `_is_leap_year(y)` | `Year.isLeap(y)` |
| `_day_length(utc_rd)` | Custom leap seconds table |

Files:
- `src/main/java/org/perlonjava/runtime/perlmodule/DateTime.java`
- CPAN `.pm` files installed via `jcpan install DateTime`

Pure Perl fallback: `DateTime::PP` — used automatically if Java XS is unavailable.

### Bundled: Time::Piece (Option A)

Files:
- `src/main/java/org/perlonjava/runtime/perlmodule/TimePiece.java`
- `src/main/perl/lib/Time/Piece.pm`
- `src/main/perl/lib/Time/Seconds.pm`

~80% of the original Perl code reused as-is. Only `_strftime`, `_strptime`, `_crt_localtime`, and similar C functions were reimplemented in Java.

---

## Testing

### Unit Tests

Create test files in `src/test/resources/` for bundled modules:

```bash
make dev                          # Quick build
./jperl src/test/resources/module_name.t
make                              # Full build + all tests
```

### Comparing with Standard Perl

```bash
cat > /tmp/test.pl << 'EOF'
use Module::Name;
# test code
EOF

perl /tmp/test.pl      # standard Perl
./jperl /tmp/test.pl   # PerlOnJava
```

### CPAN Smoke Test

Use `dev/tools/cpan_smoke_test.pl` for regression testing across modules:

```bash
perl dev/tools/cpan_smoke_test.pl --quick              # known-good modules
perl dev/tools/cpan_smoke_test.pl Moo DateTime Try::Tiny  # specific modules
perl dev/tools/cpan_smoke_test.pl --compare cpan_smoke_20250331.dat  # regressions
perl dev/tools/cpan_smoke_test.pl --list               # show all registered modules
```

Run with `perl` (not `jperl`) because it uses fork.

---

## Troubleshooting

### "Can't load loadable object for module ..."
- **Bundled**: Check class name matches naming convention, verify `initialize()` is static
- **CPAN-installed**: Check `~/.perlonjava/auto/Module/Name/Name.jar` exists
- **Both**: Module should fall back to PP if error matches `/loadable object/`

### Method Not Found
- Ensure method is registered in `initialize()`
- Check signature: `public static RuntimeList name(RuntimeArray args, int ctx)`

### Different Output Than Standard Perl
- Compare with fixed test values (not current time)
- Check locale handling
- Verify edge cases from XS comments

---

## See Also

- [XS Compatibility Reference](../reference/xs-compatibility.md) — XS modules with Java implementations and PP fallbacks
- [Using CPAN Modules](using-cpan-modules.md) — Installing and using CPAN modules with jcpan
- [Feature Matrix](../reference/feature-matrix.md) — Perl feature compatibility
- [GitHub Discussion #25](https://github.com/fglock/PerlOnJava/discussions/25) — Perl/Java module loading from project directories
