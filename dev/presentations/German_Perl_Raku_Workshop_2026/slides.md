# PerlOnJava

## Perl Running Natively on the JVM

German Perl/Raku Workshop 2026

Flavio Glock

Note:
36 slides, ~43 minutes including Q&A. Progressive depth: starts with motivation, builds through demos, then dives into compiler internals and JVM integration.

---

## The Problem

Imagine **50,000 lines of Perl** — and a mandate to move to Java or Kubernetes.

**Your options today:**
- Rewrite everything in Java
- Maintain a separate Perl runtime forever

**What if there were a third option?**

Note:
A common scenario in enterprise environments. Large Perl codebases that work well but don't fit into modern Java-based infrastructure.

---

## PerlOnJava: The Third Option

<span class="metric">A Perl compiler and runtime for the JVM</span>

- Compiles Perl to **native JVM bytecode**
- Same format as Java, Kotlin, Scala
- Not an interpreter wrapping a Perl binary
- Targets **Perl 5.42+** semantics

**Requires:** Java 21+ (any JDK)

Note:
PerlOnJava generates real JVM bytecode — the same instructions that javac produces. Perl code gets all JVM benefits: cross-platform execution, Java library access, JVM tooling.

---

## Why the JVM?

- **30 years of optimization** — JIT compilation turns hot code into native machine code
- **500K+ libraries** on Maven Central — accessible without C bindings
- **Container-aware** — built-in cgroup support for Docker/Kubernetes
- Perl joins Java, Kotlin, Scala, Clojure, JRuby on the platform

Note:
The JVM runs on 3+ billion devices. Built-in tooling (JFR flight recorder, JMX monitoring, VisualVM) works automatically on PerlOnJava code. Multiple GC strategies available (ZGC, G1, Shenandoah).

---

## What You Get

- Run existing Perl scripts **unchanged** on the JVM
- Access any **JDBC database** — no C drivers needed
- Embed Perl in Java apps via **JSR-223** scripting API
- Deploy to Docker, Kubernetes — **anywhere Java runs**
- **Interactive debugger** with step, breakpoints, stack traces

Note:
JSR-223 is the standard Java scripting API, available since Java 6. Bidirectional Java ↔ Perl communication. The debugger runs via the -d flag.

---

## One JAR, Everything Included

**`perlonjava-5.42.0.jar`** — 25 MB, zero external dependencies

```text
perlonjava.jar
├── org/perlonjava/   ← 392 Java compiled classes
├── lib/              ← 341 Perl modules (DBI, JSON, HTTP::Tiny…)
├── ASM, ICU4J        ← Java libraries bundled
└── META-INF/services ← JSR-223 auto-discovery
```

```bash
java -jar perlonjava.jar script.pl    # That's it.
./jperl script.pl                     # Or use the wrapper.
```

Note:
Built with Gradle Shadow plugin (fat JAR). Perl modules packaged as resources inside the JAR. The require mechanism reads them directly via classloader. No installation, no CPAN, no paths to configure. The jperl wrapper uses -cp so users can add extra JARs to CLASSPATH.

---

## By the Numbers

- <span class="metric">~200,000 tests</span> in the suite
- <span class="metric">~400 Java source files</span>
- <span class="metric">~6,000 commits</span> since June 2024
- Building on **29 years** of prior work: JPL → Kuhn thesis → Perlito5 → PerlOnJava

No formal Perl spec exists — the test suite **is** the specification.

Note:
JPL (1997) showed embedding was possible. Kuhn (2001) mapped the type system challenges. Perlito5 (2013-2023) proved compilation works but revealed startup and eval limitations. PerlOnJava addresses all of these.

---

## Live Demo: Database Integration

**DBI with JDBC — no C-based DBD adapters needed:**

```perl
use DBI;
my $dbh = DBI->connect(
    "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
    "sa", "", { RaiseError => 1 }
);

$dbh->do("CREATE TABLE users (name VARCHAR, age INT)");
$dbh->do("INSERT INTO users VALUES (?, ?)", undef, "Alice", 30);

my $sth = $dbh->prepare("SELECT * FROM users WHERE age > ?");
$sth->execute(20);
while (my $row = $sth->fetchrow_hashref) {
    say "$row->{name}, age $row->{age}";
}
```

**Supports:** PostgreSQL, MySQL, Oracle, SQLite, H2 — any JDBC driver.

Note:
Extended live demo — run this and additional examples interactively. Show jperl with different databases.

---

## Live Demo: Image::ExifTool

The most widely-used Perl photo metadata library — running on PerlOnJava **unmodified**.

- <span class="metric">239 Perl source files</span> · <span class="metric">296,000 lines of code</span>
- Largest modules exceed **10,000 lines** (Nikon.pm, Sony.pm, Canon.pm)
- Subroutines over **1,000 lines** (SetNewValue, WriteInfo, ExtractInfo)
- <span class="metric">600 tests</span> in 113 files — **all pass**

**Why this matters:** Large methods exceed the JVM's 64KB bytecode limit — the compiler automatically falls back to the Internal VM.

Note:
Image::ExifTool 13.44 by Phil Harvey. Modules like TagLookup.pm (13,840 LOC), Nikon.pm (12,843 LOC) stress-test every part of the compilation pipeline. The automatic dual-backend fallback is transparent — ExifTool doesn't know which backend runs each method.

---

## Bundled Ecosystem

<span class="metric">341 Perl modules</span> ship inside the JAR

DBI, HTTP::Tiny, JSON, YAML, Text::CSV, Digest::MD5, MIME::Base64…

- **Pure-Perl CPAN modules** work as-is
- **XS modules** replaced by Java equivalents (many bundled)
- Modern Perl **`class`** features (OOP)
- Dual execution backend (JVM + Internal VM)

Note:
Pure-Perl modules: copy to lib path. XS modules need Java equivalents — many already bundled. ExtUtils::MakeMaker reimplemented for PerlOnJava: jperl Makefile.PL installs to ~/.perlonjava/lib/.

---

# The Compilation Pipeline

*How do you compile a language that wasn't designed to be compiled?*

Note:
Transition to technical content. We'll walk through each stage of the pipeline, from source to execution.

---

## Pipeline Overview

**Perl 5 (traditional):**
```text
Perl Source → Lexer → Parser → OP Tree → Optimizer → Execution
```

**PerlOnJava (dual backend):**
```text
Perl Source → Lexer → Parser → AST → JVM Bytecode → JVM Execution
                                    ↘ Custom Bytecode → Internal VM
```

Shared frontend + shared runtime, two execution paths.

Note:
Perlito5 compiled Perl → Java source → bytecode. This worked, but eval STRING invoked the Java compiler at runtime. PerlOnJava generates bytecode directly — faster startup, faster eval.

---

## Lexer, Parser, and Embedded DSLs

**Lexer:** Tokenizes source. ICU4J for Unicode code points beyond U+FFFF.

**Parser — three-layer architecture:**
1. **Recursive descent** — control structures, declarations, subroutines
2. **Precedence climbing** — operators, associativity, ternary, method calls
3. **20+ specialized sub-parsers** — regex, pack, sprintf, string interpolation

**Each embedded DSL validates at compile time:**
```perl
my $name = "World";
my $msg = "Hello $name!\n";
#  → join("", "Hello ", $name, "!\n")   # Parsed at compile time
```

Note:
Perl embeds multiple DSLs: regex, pack/unpack, sprintf, transliteration, string interpolation. Each has its own parser that validates syntax and generates optimized bytecode. Use --parse to see the AST.

---

## BEGIN: The Hard Part

```perl
my @data;
BEGIN { @data = qw(a b c) }   # Compile time
say @data;                      # Runtime — must see "a b c"
```

`@data` is a **lexical** — but BEGIN runs at compile time, before runtime lexicals exist.

**Solution:** Temporary package globals as a bridge:

1. Parser **snapshots** visible lexicals → temporary globals
2. BEGIN body compiled with aliases to these globals
3. BEGIN **executes immediately** — sets the values
4. At runtime, `my @data` **retrieves** the value and cleans up

Note:
Implemented in SpecialBlockParser (capture + alias) and PersistentVariable (retrieve + cleanup). eval STRING uses ThreadLocal storage for BEGIN access to caller lexicals. use Module is sugar for BEGIN { require Module; Module->import() }.

---

## EmitterVisitor: AST → Bytecode

**What it does:**
- Traverses AST → generates JVM bytecode via **ASM library**
- Manages symbol tables
- Propagates context (void / scalar / list)

**Key challenge:** JVM imposes a **64KB method size limit**.

Large Perl subs can exceed it.

**Solution:** Automatic fallback to the Internal VM for oversized methods. Transparent to user code.

Note:
ASM is a Java library for generating bytecode programmatically. No Java source intermediate step. The 64KB limit is per-method in JVM class files. ExifTool's largest subs trigger this fallback.

---

## Seeing the Output: Disassembly

**Perl:** `my $x = 10; say $x`

**JVM bytecode** (`./jperl --disassemble`):
```text
LDC 10
INVOKESTATIC RuntimeScalarCache.getScalarInt(I)
ASTORE 7
...
INVOKESTATIC IOOperator.say(RuntimeList;RuntimeScalar)
```

**Internal VM bytecode** (`./jperl --interpreter --disassemble`):
```text
   0: LOAD_INT r4 = 10
   4: MOVE r3 = r4
  13: CREATE_LIST r7 = [r3]
  17: SAY r7, fh=r5
  24: RETURN r8
```

Note:
JVM bytecode: standard instructions, optimized by HotSpot JIT at runtime. Internal VM: register-based, ~300 opcodes, much more compact. Both share 100% of the runtime.

---

# Why Two Engines?

*JVM bytecode is fast — but three problems need a second backend.*

Note:
Transition to dual backend deep-dive. This is a common VM design pattern.

---

## Why Two Backends?

JVM bytecode is the primary path — but:

1. **64KB method size limit** — large Perl subs exceed it
2. **CPU cache pressure** — sparse JVM bytecode overflows instruction caches
3. **ClassLoader overhead** — `eval STRING` pays per-class cost

The Internal VM solves all three.

**Not unique to us:** HotSpot, V8, SpiderMonkey, CRuby all use multi-backend execution.

Note:
The dual backend is a pragmatic engineering decision, not a philosophical one. Each backend has clear strengths.

---

## JVM Backend: The Fast Path

**Loop increment, 1 billion iterations:**

| | Time | vs Perl 5 |
|---|---|---|
| **Perl 5** | 7.88s | baseline |
| **PerlOnJava** | 3.57s | **2.2× faster** |

After JIT warmup (~10K iterations), HotSpot inlines and unrolls hot loops.

**Tiered compilation:** Interpreter → C1 (fast compile) → C2 (aggressive inlining, peak performance)

Note:
Pipeline: AST → ASM → JVM bytecode → ClassLoader. C2 compiler uses profiling data to make aggressive optimizations. Default inlining thresholds: MaxInlineSize=35 bytecodes, FreqInlineSize=325.

---

## JIT-Friendly Code: Fast/Slow Path Split

**Hot path (≤ 35 bytecodes — inlineable by C2):**
```java
public int getInt() {
    if (type == INTEGER) {
        return (int) this.value;       // ~2ns
    }
    return getIntLarge();              // Slow path
}
```

**Cold path (rare cases):**
```java
private int getIntLarge() {
    return switch (type) {
        case DOUBLE  -> (int) ((double) value);
        case STRING  -> NumberParser.parseNumber(this).getInt();
        case TIED    -> this.tiedFetch().getInt();
        default      -> Overload.numify(this).getInt();
    };
}
```

90%+ calls hit INTEGER → **single-digit nanoseconds** when inlined.

Note:
This pattern is used throughout: getDouble(), toString(), getBoolean(). Keep the common case tiny so C2 can inline it. The cold path doesn't bloat the hot path's bytecode count.

---

## Internal VM: Compact and Flexible

- **Register-based** VM, ~300 opcodes, 65K virtual registers
- **No method size limits**, lower startup overhead
- Good fit for `eval`-heavy scripts

**Why register-based, not stack-based?**

Perl's non-local jumps (`goto`, `last LABEL`, `eval`) corrupt stack state. Register operands (`rd = rs1 op rs2`) stay correct regardless of control flow.

Note:
Peak throughput lower than JVM backend, but startup faster and no size constraints. Switch-based dispatch loop.

---

## eval STRING Performance

**1,000,000 unique eval strings:**

| | Time |
|---|---|
| **Perl 5** | 1.29s |
| **PerlOnJava (Internal VM)** | 2.78s |

Internal VM skips ClassLoader — compiles directly to register bytecode.

No JVM class generated per eval. Both backends share **100% of the runtime**. User code doesn't know which backend is running.

Note:
Set JPERL_EVAL_USE_INTERPRETER=1. Each iteration evals a unique string, so compilation dominates. Repeated patterns perform much closer to Perl 5.

---

# Perl Semantics on the JVM

*What makes Perl the hardest language to implement on the JVM?*

Note:
Transition to the hard implementation problems. Perl's dynamic nature fights JVM assumptions at every turn.

---

## Runtime Data Structures

**Five core classes — shared by both backends:**

**RuntimeScalar** · **RuntimeArray** · **RuntimeHash** · **RuntimeCode** · **RuntimeGlob**

**RuntimeScalar — three fields:**
- **`blessId`** — `0` unblessed, `> 0` blessed, `< 0` has overloads
- **`type`** — `INTEGER`, `DOUBLE`, `STRING`, `UNDEF`, references…
- **`value`** — the actual data (`Object`)

Note:
RuntimeScalar types: INTEGER, DOUBLE, STRING, UNDEF, BOOLEAN, TIED_SCALAR, DUALVAR. References (high bit set): CODE, ARRAYREFERENCE, HASHREFERENCE, REGEX, GLOBREFERENCE. Arrays/hashes support plain, autovivifying, tied, and read-only modes.

---

## Type Coercion, Context, and wantarray

```perl
my $x = "10";
my $y = $x + 5;        # String → number: 15
my $z = $x . " cats";  # Same $x, string context: "10 cats"
```

- **Context-driven coercion** — same variable is both string and number
- Compiler threads context (void/scalar/list) via **EmitterContext** at each call site
- `wantarray` reads the flag at runtime — works in both backends
- **Overload optimization:** negative `blessId` → check; positive → skip (~10–20ns saved)

Note:
Detection happens once at bless time. Critical because overload checks happen on nearly every operation on blessed references.

---

## Variable Scoping: Four Declarations

| Declaration | JVM Mapping | Lifetime |
|---|---|---|
| `my $x` | JVM local variable slot (`ASTORE`) | Lexical block |
| `our $x` | Alias to package global → same JVM slot | Package |
| `state $x` | Persistent via `StateVariable` registry | Sub lifetime |
| `local $x` | Save stack — snapshot + restore on exit | Dynamic scope |

All four use **JVM local variable slots** for fast access.

`local` wraps scope with `getLocalLevel()` / `popToLocalLevel()` — restores even through `die`/`eval`.

Note:
my creates fresh RuntimeScalar per call. our aliases a global into a local slot. state persists via registry keyed by __SUB__ + name. local uses DynamicVariableManager push/pop in a finally block.

---

## Closures and Lexical Capture

```perl
sub make_counter {
    my $count = 0;
    return sub { return ++$count };
}
my $c = make_counter();
say $c->();  # 1
say $c->();  # 2
```

**JVM backend:** Each anon sub → new JVM class. Visible lexicals passed as **constructor args**. Shared by reference — mutations visible to both scopes.

**Internal VM:** Dedicated opcode for closure variable allocation. Same runtime objects.

Note:
Closure capture is determined at compile time by analyzing which lexicals are referenced. Both backends share the same RuntimeScalar/RuntimeArray/RuntimeHash objects for captured variables.

---

## Control Flow: Non-Local Jumps

```perl
sub skip { last SKIP }       # Jumps out of caller's block!
SKIP: { skip() unless $ok }  # SKIP is in the caller's scope
```

**Implementation:** Tagged return values (`RuntimeControlFlowList`):
- Return carries a **control-flow tag** + target label
- Tags: `last`, `next`, `redo`, `goto`
- Caller checks tag and unwinds across subroutine boundaries

Note:
Registers maintain state explicitly. Label → bytecode offset mapping with shared handlers for multiple exits. die → Java exceptions; eval → try-catch. This is also how goto &sub (tail calls) works.

---

## caller() and Stack Traces

```perl
sub foo {
    print "Called from: ", (caller)[1], " line ", (caller)[2], "\n";
}
```

**Key trick:** `caller()` captures the **native JVM stack** via `new Throwable()` — zero cost when unused.

- **JVM backend:** Each sub → JVM class. `ByteCodeSourceMapper` maps tokens → file/line/package
- **Internal VM:** `InterpreterState` keeps a frame stack (ThreadLocal)

**No shadow stack needed** — the JVM does the bookkeeping for us.

Note:
ExceptionFormatter handles three frame types: JVM-compiled subs (anon*), Internal VM frames, and compile-time frames (CallerStack). Same mechanism powers warn/die messages.

---

## Signal Handling Without POSIX

```perl
$SIG{ALRM} = sub { die "timeout\n" };
alarm(5);
eval { long_operation() };
alarm(0);
say "Caught: $@" if $@;
```

**Challenge:** JVM has no POSIX signals. Timer threads can't safely run Perl handlers.

**Solution:** Signal queue with safe-point checking:

1. `alarm()` schedules a timer on a **daemon thread**
2. Timer **enqueues** signal + handler (`ConcurrentLinkedQueue`)
3. Compiler inserts `checkPendingSignals()` at every loop entry — volatile read, **~2 cycles**

Note:
kill() reuses this mechanism. Unix signals via jnr-posix; Windows via GenerateConsoleCtrlEvent. Handlers always execute in the original thread context.

---

## Module Loading, XSLoader, and Regex

**Module loading:** `require` → `Module/Name.pm`, searches `@INC`, caches in `%INC`.

**300+ modules bundled inside the JAR** — loaded directly via classloader:
```text
%INC: 'Data/Dumper.pm' →
  'file:/path/to/perlonjava.jar!/lib/Data/Dumper.pm'
```

**XSLoader:** Loads **Java extensions** instead of C shared libraries. No C compiler needed. `jnr-posix` replaces XS for native POSIX calls.

**Regex:** Java engine + **RegexPreprocessor** compatibility layer. All Perl modifiers (`/i`, `/g`, `/x`, `/xx`, `/e`, `/ee`). Cache of 1,000 compiled patterns.

Note:
RegexPreprocessor translates Perl syntax → Java: [:ascii:] → \p{ASCII}, multi-char case folds: ß → (?:ß|ss|SS). Unsupported: recursive patterns, variable-length lookbehind.

---

## JSR-223: Embed Perl in Java

```java
ScriptEngineManager manager = new ScriptEngineManager();
ScriptEngine perl = manager.getEngineByName("perl");

perl.put("data", myJavaObject);
Object result = perl.eval("process_data($data)");
```

**Bidirectional:** Java ↔ Perl seamlessly.

**Use case:** Embed legacy Perl scripts in a modern Java application — without rewriting them.

Note:
JSR-223 is the standard Java scripting API, part of the JDK since Java 6. PerlOnJava registers via META-INF/services for auto-discovery. Any Java application can use Perl as a scripting language.

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
Debugger uses Internal VM (forced with -d). DEBUG opcodes inserted at each statement. DebugHooks handles breakpoints, command parsing, and eval in current scope.

---

## Current Limitations & Roadmap

**JVM-incompatible:**
- `fork` — not available on JVM
- `DESTROY` — non-deterministic GC
- Threading — not yet implemented

**Next:**
- Internal VM optimization, `eval STRING` performance
- More compatible regex engine
- GraalVM native images · Android DEX

Note:
Workarounds: jnr-posix for native access, Java threading APIs. The Internal VM is key to multi-platform — custom bytecode is portable to any JVM derivative.

---

## Perl Was Never Designed to Run on the JVM

We made it work anyway — and made it **fast**.

<span class="metric">~200,000 tests</span> · <span class="metric">400 files</span> · <span class="metric">6,000 commits</span>

No formal spec exists. The tests **are** the specification.

Note:
Test-driven development at its most extreme — tests define the language behavior. Every edge case is validated against Perl 5's own test suite.

---

## Thank You!

**GitHub:** github.com/fglock/PerlOnJava · **License:** Artistic 2.0

**Get involved:**
- **Test** your scripts and report issues
- **Port** CPAN modules
- **Contribute** to core development

**Special thanks to:**
Larry Wall · Perl test writers · Perl community · JPL, perljvm, Perlito5 pioneers

**Questions?** → github.com/fglock/PerlOnJava/issues

---
