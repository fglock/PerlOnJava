# PerlOnJava — Integration & Future

## Making Perl a First-Class JVM Citizen

German Perl/Raku Workshop 2026 — Flavio Glock

*Part 3: Integration, tooling, and roadmap (10min)*

---

## XSLoader: Java Instead of C

- Loads **Java extensions** instead of C shared libraries
- **jnr-posix** replaces XS for native POSIX calls
- No C compiler needed

Note:
Java equivalents are easier to write and maintain than C/XS. The same API surface is exposed to Perl code.

---

## CPAN Installation

**ExtUtils::MakeMaker reimplemented for PerlOnJava:**

```bash
tar xzf Some-Module-1.00.tar.gz
cd Some-Module-1.00
jperl Makefile.PL   # installs to ~/.perlonjava/lib/
```

- **Pure Perl modules:** copied directly, no `make` needed
- **XS modules:** detected, guidance provided for Java porting
- **300+ modules** bundled in JAR

**Open question:** automated XS→Java via LLM

---

## Module Loading

`require` converts `Module::Name` → `Module/Name.pm`, searches `@INC`, caches in `%INC`.

**300+ modules bundled inside the JAR:**
```text
%INC: 'Data/Dumper.pm' =>
  'file:/path/to/perlonjava.jar!/lib/Data/Dumper.pm'
```

---

## JSR-223: Embed Perl in Java

JSR-223 is the standard Java scripting API (JDK since Java 6).

```java
ScriptEngineManager manager = new ScriptEngineManager();
ScriptEngine perl = manager.getEngineByName("perl");

perl.put("data", myJavaObject);
Object result = perl.eval("process_data($data)");
```

**Bidirectional:** Java ↔ Perl seamlessly.

**Use case:** Embed legacy Perl scripts in a modern Java application without rewriting them.

---

## Future Targets

**Current:** Standard JVM (HotSpot)

1. **GraalVM** — native executables, instant startup
2. **Android DEX** — Perl on mobile devices

The Internal VM is key — custom bytecode is portable to any JVM derivative.

Note:
Dual backend matters beyond performance. GraalVM gives standalone executables. Android DEX converts JVM to Dalvik bytecode.

---

## Interactive Debugger

**Invoke with `-d` flag:** `./jperl -d script.pl`

```text
  n        Step over (next line)
  s        Step into subroutine
  r        Step out (return)
  c        Continue to breakpoint
  b 42     Set breakpoint at line 42
  l        List source around current line
  T        Stack trace
  p $var   Print variable value
```

Supports `$DB::single`, `@DB::args`, `%DB::sub`, and custom `DB::DB` via `PERL5DB`.

Note:
Debugger uses Internal VM (forced with -d). DEBUG opcodes inserted at each statement. DebugHooks handles breakpoints, command parsing, and eval in current scope. PERL5DB supported for custom debuggers.

---

## Current Limitations

**JVM-incompatible:**
- `fork` — not available on JVM
- `DESTROY` — JVM uses non-deterministic GC
- Threading — not yet implemented

**Partially implemented:**
- Some regex features, taint checks

Note:
Workarounds: jnr-posix for native access, Java threading APIs, file auto-close at exit. XS modules use Java equivalents.

---

## Roadmap

**Stable now:** JVM backend, Perl class features, IPC, sockets, interactive debugger

**In progress:** Internal VM optimization, eval STRING performance

**Next:** More compatible regex engine, additional debugger features

---

# Closing

---

## Perl Was Never Designed to Run on the JVM

We made it work anyway — and made it **fast**.

<span class="metric">~200,000 tests</span> · <span class="metric">400 files</span> · <span class="metric">6,000 commits</span>

No formal spec exists. The tests **are** the specification.

Note:
This is test-driven development at its most extreme — tests define the language behavior.

---

## Get Involved

**GitHub:** github.com/fglock/PerlOnJava · **License:** Artistic 2.0

- **Test** your scripts and report issues
- **Port** CPAN modules
- **Contribute** to core development

---

## Thank You!

**Special thanks to:**

- **Larry Wall** — for creating Perl
- **Perl test writers** — tests that define Perl's behavior
- **Perl community** — for decades of innovation
- **Prior pioneers** — JPL, perljvm, Perlito5

**Questions?** → github.com/fglock/PerlOnJava/issues

---
