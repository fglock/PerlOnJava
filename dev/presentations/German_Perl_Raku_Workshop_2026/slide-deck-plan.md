# Unified 36-Slide Deck Plan

## PerlOnJava: Perl Running Natively on the JVM
**German Perl/Raku Workshop 2026 — Flavio Glock**

---

## Design Principles

- **Progressive depth:** Start with "why" and "what", then "how", then deep internals
- **Short sentences:** Max 2 lines per bullet. No walls of text.
- **Code over prose:** Show real Perl and bytecode output whenever possible
- **Audience-calibrated:** Experienced Perl programmers, medium JVM/compiler knowledge — explain JVM concepts briefly, never explain Perl concepts
- **Professional look:** Consistent structure per slide (title + 3–5 bullets or 1 code block + 2 bullets)
- **One idea per slide:** No overloaded slides

---

## Narrative Arc

| Act | Slides | Goal |
|-----|--------|------|
| **I. Hook & Vision** | 1–6 | Why this exists, what it delivers |
| **II. See It Work** | 7–10 | Live demos, concrete proof (extended demo time) |
| **III. The Pipeline** | 11–16 | How Perl becomes JVM bytecode |
| **IV. Dual Backends** | 17–22 | Why two engines, when each wins |
| **V. Hard Problems** | 23–30 | Perl semantics mapped to JVM |
| **VI. Integration & Future** | 31–36 | Ecosystem, tooling, roadmap, close |

---

## Slide-by-Slide Outline

### Act I: Hook & Vision (slides 1–6)

**Slide 1 — Title**
- PerlOnJava: Perl Running Natively on the JVM
- German Perl/Raku Workshop 2026 — Flavio Glock

**Slide 2 — The Problem**
- 50K lines of Perl + mandate to move to Java/Kubernetes
- Options today: rewrite everything, or maintain a separate Perl runtime forever
- What if there were a third option?
- _Hook: relatable enterprise pain point_

**Slide 3 — The Third Option**
- Perl compiler and runtime for the JVM
- Compiles to native JVM bytecode — same as Java, Kotlin, Scala
- Not an interpreter wrapping a Perl binary
- Targets Perl 5.42+ semantics. Requires Java 22+.

**Slide 4 — Why the JVM?**
- 30 years of JIT optimization — hot code becomes native machine code
- 500K+ Maven Central libraries — no C bindings needed
- Container-aware — built-in Docker/Kubernetes support
- Joins Java, Kotlin, Scala, Clojure, JRuby on the platform

**Slide 5 — What You Get**
- Run existing Perl scripts unchanged
- Access any JDBC database — no C drivers
- Embed Perl in Java apps via JSR-223
- Deploy anywhere Java runs
- Interactive debugger (`-d`)

**Slide 6 — One JAR, Everything Included**
- `perlonjava-5.42.0.jar` — 25 MB, zero external dependencies
- Diagram: 392 compiled classes + 341 Perl modules + bundled Java libs
- `java -jar perlonjava.jar script.pl` — that's it
- _Establishes simplicity before going deeper_

### Act II: See It Work (slides 7–10)

**Slide 7 — By the Numbers**
- ~200,000 tests in the suite
- ~400 Java source files, ~6,000 commits since June 2024
- Building on 29 years of prior work: JPL → Kuhn thesis → Perlito5 → PerlOnJava
- No formal Perl spec — the test suite IS the specification

**Slide 8 — Live Demo: DBI with JDBC**
- Code example: DBI->connect with JDBC URL, INSERT, SELECT
- Supports PostgreSQL, MySQL, Oracle, SQLite, H2
- No C-based DBD adapters needed
- _Extended live demo time — run multiple examples interactively_

**Slide 9 — Live Demo: Image::ExifTool**
- 239 Perl files, 296K lines — running unmodified
- 600 tests in 113 files — all pass
- Largest modules exceed 10,000 lines; subs over 1,000 lines
- Why it matters: stress-tests the entire compilation pipeline

**Slide 10 — Bundled Ecosystem**
- 341 Perl modules ship inside the JAR
- DBI, HTTP::Tiny, JSON, YAML, Text::CSV, Digest::MD5…
- Pure-Perl CPAN modules work as-is
- XS modules replaced by Java equivalents

### Act III: The Pipeline (slides 11–16)

**Slide 11 — Section Divider: Compilation Pipeline**
- "How do you compile a language that wasn't designed to be compiled?"

**Slide 12 — Pipeline Overview**
- Diagram: Perl Source → Lexer → Parser → AST → JVM Bytecode → Execution
- Secondary path: AST → Custom Bytecode → Internal VM
- Shared frontend + shared runtime, two execution paths
- Contrast with Perl 5 traditional: Source → Lexer → Parser → OP Tree → Execution

**Slide 13 — Lexer, Parser, and Embedded DSLs**
- Lexer: tokenizes source, handles Unicode (ICU4J for surrogate pairs)
- Parser: three-layer architecture
  - Recursive descent (control structures, declarations)
  - Precedence climbing (operators, associativity)
  - 20+ specialized sub-parsers: regex, pack, sprintf, string interpolation
- Each embedded DSL validates at compile time → optimized bytecode
- Code example: string interpolation → AST (from `--parse`)

**Slide 14 — BEGIN: The Hard Part**
- `BEGIN { @data = qw(a b c) }` runs at compile time
- But `@data` is a lexical — doesn't exist at compile time yet
- Solution: temporary package globals as a bridge
  1. Snapshot visible lexicals
  2. Alias to temporary globals
  3. BEGIN executes immediately
  4. Runtime retrieves values and cleans up

**Slide 15 — EmitterVisitor: AST → Bytecode**
- Traverses AST, generates JVM bytecode via ASM library
- Manages symbol tables, propagates context (void/scalar/list)
- Key challenge: JVM's 64KB method size limit
- Solution: automatic fallback to Internal VM for oversized methods

**Slide 16 — Seeing the Output: Disassembly**
- Side-by-side: `my $x = 10; say $x`
- Left: JVM bytecode (LDC, INVOKESTATIC, ASTORE…)
- Right: Internal VM bytecode (LOAD_INT r4=10, MOVE, SAY…)
- Shows both backends from the same Perl source

### Act IV: Dual Backends (slides 17–22)

**Slide 17 — Section Divider: Why Two Engines?**
- "JVM bytecode is fast — but three problems need a second backend"

**Slide 18 — Why Two Backends?**
- 64KB method size limit — large Perl subs exceed it
- CPU cache pressure — sparse JVM bytecode overflows instruction caches
- ClassLoader overhead — `eval STRING` pays per-class cost
- Common VM pattern: HotSpot, V8, SpiderMonkey, CRuby all do this

**Slide 19 — JVM Backend: The Fast Path**
- Benchmark: loop increment, 1B iterations — PerlOnJava 3.57s vs Perl 5 7.88s (2× faster)
- After JIT warmup (~10K iterations), JVM inlines and unrolls hot loops
- HotSpot tiered compilation: Interpreter → C1 → C2 (aggressive inlining)

**Slide 20 — JIT-Friendly Code: Fast/Slow Path Split**
- Code example: `getInt()` hot path ≤35 bytecodes → inlineable
- Cold path handles rare cases (double, string, tied, overloaded)
- 90%+ calls hit INTEGER → single-digit nanoseconds when inlined
- Pattern used throughout: getDouble(), toString(), getBoolean()

**Slide 21 — Internal VM: Compact and Flexible**
- Register-based VM, ~300 opcodes, 65K virtual registers
- No method size limits, lower startup overhead
- Why register-based: Perl's non-local jumps (goto, last LABEL, eval) corrupt stack state
- Explicit operands (`rd = rs1 op rs2`) stay correct regardless of control flow

**Slide 22 — eval STRING Performance**
- Benchmark: 1M unique eval strings — Perl 5: 1.29s, PerlOnJava: 2.78s
- Internal VM skips ClassLoader — compiles directly to register bytecode
- No JVM class generated per eval
- Both backends share 100% of the runtime — user code doesn't know which runs

### Act V: Hard Problems (slides 23–30)

**Slide 23 — Section Divider: Perl Semantics on the JVM**
- "What makes Perl the hardest language to implement on the JVM?"

**Slide 24 — Runtime Data Structures**
- Five core classes shared by both backends:
  - RuntimeScalar (dynamically typed value)
  - RuntimeArray / RuntimeHash
  - RuntimeCode (MethodHandle or InterpretedCode)
  - RuntimeGlob (typeglob with slot delegation)
- RuntimeScalar: 3 fields — blessId, type, value

**Slide 25 — Type Coercion, Context, and wantarray**
- Code example: `$x = "10"; $y = $x + 5; $z = $x . " cats"`
- Same variable is both string and number — context-driven coercion
- Compiler threads context (void/scalar/list) via EmitterContext at each call site
- `wantarray` reads the flag at runtime — works identically in both backends
- Overload optimization: negative blessId → has overloads; positive → skip check

**Slide 26 — Variable Scoping: Four Declarations**
- Table: my → JVM local slot, our → alias to global, state → persistent registry, local → save stack
- All four use JVM local variable slots for fast access
- Difference is lifetime and initialization
- `local` uses DynamicVariableManager push/pop with finally block

**Slide 27 — Closures and Lexical Capture**
- Code example: make_counter() returning closure
- JVM backend: each anon sub → new JVM class, lexicals passed as constructor args
- Shared by reference — mutations visible to both scopes
- Internal VM: dedicated opcode for closure variable allocation

**Slide 28 — Control Flow: Non-Local Jumps**
- Code example: `sub skip { last SKIP }` — jumps out of caller's block
- Implementation: tagged return values (RuntimeControlFlowList)
- Return carries control-flow tag + target label
- Register architecture essential — stack state would corrupt

**Slide 29 — caller() and Stack Traces**
- `caller()` captures native JVM stack via `new Throwable()` — zero cost when unused
- JVM backend: ByteCodeSourceMapper maps tokens → file/line/package
- Internal VM: InterpreterState frame stack (ThreadLocal)
- No shadow stack needed — the JVM does the bookkeeping

**Slide 30 — Signal Handling Without POSIX**
- JVM has no POSIX signals — timer threads can't safely run Perl handlers
- Solution: signal queue with safe-point checking
  - alarm() schedules a daemon thread timer
  - Timer enqueues signal (ConcurrentLinkedQueue)
  - Compiler inserts checkPendingSignals() at every loop entry
  - Volatile boolean read: ~2 CPU cycles, zero cost when idle

### Act VI: Integration & Future (slides 31–36)

**Slide 31 — Module Loading, XSLoader, and Regex**
- require: Module::Name → Module/Name.pm, searches @INC, caches in %INC
- 300+ modules bundled inside JAR — loaded via classloader
- XSLoader loads Java extensions instead of C shared libraries
- Regex: Java engine + Perl compatibility layer (RegexPreprocessor) — all modifiers, 1000-pattern cache

**Slide 32 — JSR-223: Embed Perl in Java**
- Code example: ScriptEngine perl = manager.getEngineByName("perl")
- Bidirectional: Java ↔ Perl seamlessly
- Use case: embed legacy Perl scripts in modern Java apps without rewriting

**Slide 33 — Interactive Debugger**
- Invoke with `-d` flag
- Commands: n (step over), s (step into), b (breakpoint), T (stack trace), p (print)
- Supports $DB::single, @DB::args, PERL5DB
- Uses Internal VM — DEBUG opcodes at each statement

**Slide 34 — Current Limitations & Roadmap**
- JVM-incompatible: fork, threading
- Partially implemented: some regex features, taint checks
- In progress: Internal VM optimization, eval STRING performance
- Next: more compatible regex engine, GraalVM native images, Android DEX

**Slide 35 — Closing Statement**
- Perl was never designed to run on the JVM. We made it work — and made it fast.
- ~200,000 tests · 400 files · 6,000 commits
- No formal spec. The tests ARE the specification.

**Slide 36 — Thank You & Get Involved**
- GitHub: github.com/fglock/PerlOnJava — License: Artistic 2.0
- Test your scripts, port CPAN modules, contribute to core
- Thanks to Larry Wall, Perl test writers, community, prior pioneers
- Questions? → github.com/fglock/PerlOnJava/issues

---

## What Was Cut (from original 54 slides)

- **Merged:** Getting Started + One JAR (redundant setup info)
- **Merged:** Three RuntimeScalar slides → one slide + type coercion
- **Merged:** Two control flow slides → one
- **Merged:** Unicode slide → folded into regex and type coercion
- **Merged:** Typeglobs → folded into runtime data structures note
- **Merged:** tie → cut (niche for this audience; mention in notes only)
- **Merged:** local (dedicated slide) → folded into variable scoping slide
- **Merged:** 29 Years in the Making → folded into By the Numbers as one bullet
- **Merged:** Sublanguage Parsing → folded into Lexer and Parser slide
- **Merged:** Context Propagation / wantarray → folded into Type Coercion and Context
- **Merged:** Regular Expressions → folded into Module Loading / XSLoader slide
- **Removed:** Redundant "What's Next: Part 2/3" transition slides (3 slides)
- **Removed:** Duplicate key takeaway slides (2 of 3 — keep only closing)
- **Removed:** CPAN installation details (niche — mention in notes)
- **Removed:** Separate "How It Works: AST" and "How It Works: Bytecode" intro slides (merged into pipeline)

## Slide Timing Estimate

- **Acts I–II** (10 slides): ~14 minutes — motivate and demonstrate (extended live demo)
- **Act III** (6 slides): ~7 minutes — pipeline walkthrough
- **Act IV** (6 slides): ~7 minutes — dual backend deep-dive
- **Act V** (8 slides): ~9 minutes — hard implementation problems
- **Act VI** (6 slides): ~6 minutes — integration, roadmap, close
- **Total:** ~43 minutes including Q&A buffer
- **Live demo buffer:** Extra ~4 minutes freed vs 40-slide version

## Style Notes

- Every slide: **title + max 5 bullets** or **title + code block + 2 bullets**
- Code blocks: use real PerlOnJava output (--disassemble, --parse)
- No decorative graphics — diagrams only where they clarify (pipeline, data structures)
- Speaker notes for every slide with extra detail for Q&A
- Use `<span class="metric">` for key numbers to enable CSS highlighting
