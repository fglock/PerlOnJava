# Dual-Backend CPAN Modules (Option B)

## Overview

This document describes the plan to support CPAN modules that ship a `.java` file
alongside the traditional `.xs`, allowing the same distribution to work on both
standard Perl (`perl`) and PerlOnJava (`jperl`).

**Related documentation:** [Module Porting Guide](../../docs/guides/module-porting.md) —
update the "Status: Not yet implemented" note there when each phase is completed.

---

## Motivation

Currently, Java XS modules must be bundled inside the PerlOnJava JAR (Option A).
This limits Java XS authorship to the PerlOnJava project itself. Option B enables
any CPAN module author to ship a Java backend without depending on PerlOnJava
releases.

See also: [GitHub Discussion #25](https://github.com/fglock/PerlOnJava/discussions/25)

---

## Architecture

### CPAN Distribution Layout

A dual-backend module ships three implementations in the same tarball:

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
│   └── META-INF/
│       └── perlonjava.properties   # Manifest for jcpan
├── Bar.xs                   # C XS implementation for standard Perl
├── Makefile.PL
├── t/
│   └── basic.t
└── META.json
```

The `java/` directory uses Perl module paths (not Java package paths) for
familiarity with Perl authors.

### Install-Time Compilation

When `jcpan install Foo::Bar` encounters a `java/` directory:

1. Copy `.pm` files to `~/.perlonjava/lib/` (existing behavior)
2. Read `java/META-INF/perlonjava.properties` for module metadata
3. Compile `.java` against `perlonjava.jar`:
   ```bash
   javac -cp perlonjava.jar -d /tmp/build java/Foo/Bar.java
   jar cf ~/.perlonjava/auto/Foo/Bar/Bar.jar -C /tmp/build .
   ```
4. Copy source to `~/.perlonjava/auto/Foo/Bar/Bar.java` (for recompilation)

### Install Layout

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

This mirrors Perl's `auto/Module/Name/Name.so` convention.

### XSLoader Search Order

When `XSLoader::load('Foo::Bar')` is called:

1. **Built-in registry** — Java classes in the PerlOnJava JAR
   (`org.perlonjava.runtime.perlmodule.*`)
2. **`auto/` JARs** — `~/.perlonjava/auto/Foo/Bar/Bar.jar`
3. **Fail** — die with `"Can't load loadable object for module Foo::Bar"`
   (triggers PP fallback if the module uses the standard eval/require pattern)

### Manifest Format

```properties
# java/META-INF/perlonjava.properties
perl-module=Foo::Bar
main-class=org.perlonjava.cpan.foo.Bar
```

- `perl-module` — the Perl package name (used for `auto/` path calculation)
- `main-class` — the fully-qualified Java class name (used for dynamic loading)

---

## Implementation Plan

### Phase 1: XSLoader `auto/` JAR Discovery

**Goal:** Teach `XSLoader.java` to find and load JARs from `~/.perlonjava/auto/`.

**Changes:**
- `src/main/java/org/perlonjava/runtime/perlmodule/XSLoader.java`
  - After the built-in registry lookup fails, check for
    `~/.perlonjava/auto/<module-path>/<leaf>.jar`
  - Use `DynamicClassLoader.loadJar()` to add the JAR to the classpath
  - Read `META-INF/perlonjava.properties` from the JAR to find the main class
  - Call the static `initialize()` method on the main class

**Test:**
```bash
# Manually place a pre-compiled JAR and verify XSLoader finds it
mkdir -p ~/.perlonjava/auto/Test/JavaXS/
cp TestJavaXS.jar ~/.perlonjava/auto/Test/JavaXS/
./jperl -e 'use Test::JavaXS; print Test::JavaXS::hello(), "\n"'
```

### Phase 2: jcpan Java Compilation Support

**Goal:** Teach `jcpan` / `ExtUtils::MakeMaker.pm` to detect and compile `java/` directories.

**Changes:**
- `src/main/perl/lib/ExtUtils/MakeMaker.pm`
  - In `_handle_xs_module()` (or a new `_handle_java_xs()`), detect `java/` directory
  - Read `java/META-INF/perlonjava.properties`
  - Invoke `javac` to compile the `.java` file against `perlonjava.jar`
  - Package into a JAR and install to `~/.perlonjava/auto/`
  - Copy source `.java` file alongside the JAR

**Dependencies:**
- Requires a JDK (not just JRE) on the user's machine
- `perlonjava.jar` path must be discoverable (e.g., from `$0` or an env var)

**Test:**
```bash
# Create a minimal dual-backend distribution and install it
jcpan install /tmp/Test-JavaXS-1.00/
./jperl -e 'use Test::JavaXS; print Test::JavaXS::hello(), "\n"'
```

### Phase 3: Recompilation on JDK Upgrade

**Goal:** Detect stale JARs and recompile from saved source.

**Changes:**
- Store the Java version used for compilation in
  `~/.perlonjava/auto/Foo/Bar/Bar.jar.meta`
- On load failure (e.g., `UnsupportedClassVersionError`), attempt recompilation
  from the saved `.java` source

**This phase is optional and can be deferred.**

### Phase 4: Documentation and Ecosystem

**Goal:** Make it easy for CPAN authors to add Java XS support.

**Deliverables:**
- Example dual-backend distribution on GitHub
- Template `java/META-INF/perlonjava.properties`
- Blog post / announcement
- **Update `docs/guides/module-porting.md`** — remove the "Not yet implemented"
  warning from Option B

---

## Open Questions

1. **Java package naming for CPAN modules** — Should we enforce
   `org.perlonjava.cpan.<module>` or allow any package? The manifest makes
   arbitrary packages possible.

2. **Multiple Java files** — Some modules may need multiple `.java` files.
   Should `jcpan` compile all `.java` files in the `java/` tree?

3. **Java dependency JARs** — If a Java XS module depends on third-party JARs
   (e.g., a JDBC driver), how should those be specified and installed?
   Possible: `java/lib/*.jar` directory, or a `java/dependencies.txt` manifest.

4. **`CLASSPATH` for project-local modules** — For users who want to load their
   own Java classes without going through CPAN, the `Java::System::load_class`
   API (proposed in Discussion #25) is a separate but complementary feature.

---

## Progress Tracking

### Current Status: Not started

### Phases
- [ ] Phase 1: XSLoader `auto/` JAR discovery
- [ ] Phase 2: jcpan Java compilation support
- [ ] Phase 3: Recompilation on JDK upgrade (optional)
- [ ] Phase 4: Documentation and ecosystem

### Reminders
- When Phase 1 is complete, update `docs/guides/module-porting.md` to note
  that `auto/` JAR loading is functional
- When Phase 4 is complete, remove the "Not yet implemented" warning from
  `docs/guides/module-porting.md`

### Action Items

- [ ] **Create a GitHub issue** to track implementation of Dual-Backend CPAN Module support.
  The issue should include:
  - Summary of the feature: allow CPAN modules to ship a `java/` directory with
    Java XS implementations that `jcpan` compiles at install time
  - The 4-phase implementation plan (XSLoader discovery, jcpan compilation,
    recompilation, documentation)
  - Links to:
    - This design doc: `dev/design/DUAL_BACKEND_CPAN_MODULES.md`
    - Module porting guide: `docs/guides/module-porting.md` (Option B section)
    - Discussion #25: https://github.com/fglock/PerlOnJava/discussions/25
  - Open questions from this document
  - Label: `enhancement`

- [ ] **Reply to [Discussion #25](https://github.com/fglock/PerlOnJava/discussions/25)**
  with the following:
  - A GitHub issue has been opened to implement support for dual-backend CPAN
    modules (link to the issue)
  - The module porting guide now documents a proposed "Publish a Dual-Backend
    CPAN Module" workflow (not yet implemented):
    https://github.com/fglock/PerlOnJava/blob/master/docs/guides/module-porting.md
  - A detailed implementation plan has been created:
    https://github.com/fglock/PerlOnJava/blob/master/dev/design/DUAL_BACKEND_CPAN_MODULES.md
  - Invite feedback on the proposed `java/` directory convention and `auto/` install layout

---

## Related Documents

- [Module Porting Guide](../../docs/guides/module-porting.md) — user-facing documentation
- [XS Fallback Mechanism](../modules/xs_fallback.md) — how XSLoader fallback works
- [XSLoader Architecture](../modules/xsloader.md) — XSLoader internals
- [CPAN Client Support](../modules/cpan_client.md) — jcpan implementation
- [GitHub Discussion #25](https://github.com/fglock/PerlOnJava/discussions/25) — original feature request
