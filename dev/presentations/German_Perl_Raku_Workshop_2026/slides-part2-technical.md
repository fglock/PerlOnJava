# PerlOnJava — Technical Deep-Dive

## From Perl Source to JVM Bytecode

German Perl/Raku Workshop 2026 — Flavio Glock

*Part 2: 40-minute technical talk — progressively more technical*

---

# Section 1: Compilation Pipeline

---

## Compilation Approaches

**Perl 5 (traditional):**
- Builds OP tree → peephole optimizations → runs
- No clean phase separation — organically evolved

**PerlOnJava (dual backend):**
```
Perl Source → Compiler → JVM Bytecode → JVM Execution
                       ↘ Custom Bytecode → Internal VM
```

A multi-backend compiler with shared frontend + shared runtime.

**Why not transpile to Java source?** Perlito5 (predecessor) compiled Perl → Java → bytecode. Efficient, but slower startup and `eval STRING` invokes the Java compiler at runtime. PerlOnJava generates bytecode directly.

---

## Lexer: Tokenization

Breaks source into tokens: identifiers, operators, keywords.

**Challenge:** Unicode identifiers

```perl
my $café_123 = 42;        # Valid
my $𝕦𝕟𝕚𝕔𝕠𝕕𝕖 = "test";      # Surrogate pairs
```

**Solution:** IBM's ICU4J library for Unicode code points (not UTF-16 units). Perl limits identifiers to 251 code points; characters beyond U+FFFF need surrogate pair handling.

---

## Parser: Building the AST

**Three-layer architecture:**

1. **Recursive descent** (`StatementParser`, `ParseBlock`)
   - Control structures, declarations, subroutines, special blocks

2. **Precedence climbing** (`Parser.parseExpression`)
   - Operator precedence, associativity, ternary, method calls

3. **Operator-specific parsers** (20+ specialized)
   - `StringParser`, `PackParser`, `SprintfFormatParser`
   - `NumberParser`, `IdentifierParser`, `SignatureParser`

Produces AST nodes: `BlockNode`, `BinaryOperatorNode`, `ListNode`. Modular — easy to add new syntax.

---

## Special Blocks: BEGIN, END, INIT, CHECK

**BEGIN blocks execute at compile time:**

```perl
my @data;
BEGIN {
    @data = qw(a b c);  # Runs during compilation
    say "Compiling...";
}
say @data;  # Uses data set by BEGIN
```

**Mechanism:** Parser encounters `BEGIN` → wraps as anonymous sub → **executes immediately** during parsing → captures lexical variables.

`use Module` is sugar for `BEGIN { require Module; Module->import() }` — so `use strict` runs at compile time.

---

## Special Blocks (continued)

**Other special blocks:**
- **END** — runs at program exit (saved for later)
- **INIT** — runs after compilation, before runtime
- **CHECK** — runs after compilation (reverse order)
- **UNITCHECK** — runs after each compilation unit

Behavior follows the Perl test suite; some edge cases not yet implemented.

---

## EmitterVisitor: Bytecode Generation

**What it does:**
- Traverses AST → generates JVM bytecode via ASM library
- Manages symbol tables
- Context propagation (void/scalar/list)

**Key challenge:** The JVM imposes a **64KB limit per method**. Large Perl subroutines can exceed this.

**Solution:** Automatic fallback to the Internal VM for oversized methods.

---

## Sublanguage Parsing

**Perl embeds multiple DSLs — each with its own parser:**

1. Regular expressions: `m/pattern/flags`
2. Pack/unpack templates: `pack("N*", @values)`
3. Sprintf format strings: `sprintf("%d", $n)`
4. Transliteration: `tr/a-z/A-Z/`
5. String interpolation: `"Value: $x\n"`

Each parser validates at compile time, generates optimized bytecode, and provides consistent error messages. Invoked by the main parser when context requires it.

---

## Sublanguage Example: String Interpolation

```perl
my $name = "World";
my $msg = "Hello $name!\n";
```

**Generated AST** (from `./jperl --parse`):
```
BinaryOperatorNode: =
  OperatorNode: my
    OperatorNode: $
      IdentifierNode: 'msg'
  BinaryOperatorNode: join
    StringNode: empty
    ListNode:
      StringNode: 'Hello '
      OperatorNode: $
        IdentifierNode: 'name'
      StringNode: '!\x0A'
```

Compile-time validation catches errors early.

---

# Section 2: Dual Execution Model

You saw both bytecode outputs in Part 1. Now: *why* two backends, and *how* they're optimized.

---

## Why Two Backends?

**JVM bytecode is the primary path — maximum performance via JIT.**

**But three problems push us toward a second backend:**

1. **64KB method size limit** — large Perl subs can exceed it. The Internal VM has no such limit.

2. **CPU cache pressure** — very large JVM methods produce sparse bytecode that overflows instruction caches. Compact Internal VM bytecode is more cache-efficient.

3. **ClassLoader bottleneck for `eval STRING`** — JVM class loader has overhead per class. The Internal VM avoids this, reducing eval latency.

This is a common VM design pattern: HotSpot, V8, SpiderMonkey, CRuby all use tiered execution.

---

## JVM Backend: The Fast Path

**Benchmark (loop increment, 1 billion iterations):**
- Perl 5: 7.88s
- PerlOnJava JVM Backend: **3.57s (2.2× faster)**

**Advantages:**
- High throughput after JIT warmup
- Native JVM stack frames
- JVM optimizations (inlining, loop unrolling)

**Pipeline:** AST → ASM → JVM bytecode → ClassLoader. After warmup (~10K iterations), reaches peak performance. Best for long-running code.

---

## JVM Tiered Compilation

**HotSpot progressively optimizes hot code through 5 tiers:**

- **Tier 0: Interpreter** — bytecode interpretation, collects profiling data
- **Tier 1–3: C1 Compiler** — fast compilation, increasing profiling
- **Tier 4: C2 Compiler** — aggressive inlining, loop optimizations, peak performance

**Key insight for PerlOnJava:** Keep critical paths small (≤ 35 bytecodes) for aggressive inlining. This is why we split methods into fast/slow paths.

Default thresholds: `MaxInlineSize=35`, `FreqInlineSize=325`.

---

## Optimization Example: Fast/Slow Path Split

**Hot path (≤ 35 bytecodes — inlineable):**
```java
public int getInt() {
    if (type == INTEGER) {
        return (int) this.value;  // ~2ns
    }
    return getIntLarge();  // Call slow path
}
```

**Cold path (rare cases):**
```java
private int getIntLarge() {
    return switch (type) {
        case DOUBLE -> (int) ((double) value);
        case STRING -> NumberParser.parseNumber(this).getInt();
        case TIED_SCALAR -> this.tiedFetch().getInt();
        default -> Overload.numify(this).getInt();
    };
}
```

90%+ calls hit INTEGER. Fast path: single-digit nanoseconds when inlined. Pattern used throughout: `getDouble()`, `toString()`, `getBoolean()`.

---

## Custom Bytecode Backend: Compact and Flexible

**Characteristics:**
- Lower startup overhead — no JVM class generation
- Peak throughput lower than JVM backend
- Good fit for eval-heavy scripts

**Advantages:**
- No method size limits
- Compact bytecode footprint
- 65,536 virtual registers

Switch-based VM with ~200 opcodes covering all Perl operations.

---

## Why Register-Based?

```perl
eval {
    for my $i (1..10) {
        goto LABEL if $i > 5;
    }
};
LABEL: print "Jumped out!\n";
```

- **Stack-based:** Stack state becomes inconsistent on non-local jumps
- **Register-based:** Explicit operands (`rd = rs1 op rs2`) maintain correctness regardless of control flow

Perl's complex control flow — labeled loops, `goto`, `eval` — requires register architecture.

---

## eval STRING Performance

**Dynamic eval (1,000,000 unique strings, Internal VM):**

| Implementation | Time | vs Perl 5 |
|----------------|------|-----------|
| Perl 5 | 1.29s | baseline |
| PerlOnJava | 2.78s | 2.16× slower |

Each iteration evals a different string — compilation overhead dominates. Still functional for typical eval usage. Internal VM avoids classloader overhead (`JPERL_EVAL_USE_INTERPRETER=1`).

---

# Section 3: Complex Language Features

Perl *semantics* on the JVM — the hardest parts.

---

## Runtime Data Structures

**Core classes (shared by both backends):**

1. **RuntimeScalar** — dynamically typed: integer, double, string, reference, undef, regex, glob, tied, dualvar
2. **RuntimeArray** — dynamic list; plain, autovivifying, tied, read-only modes
3. **RuntimeHash** — associative array; plain, autovivifying, tied modes
4. **RuntimeCode** — compiled sub; holds JVM `MethodHandle` or `InterpretedCode`
5. **RuntimeGlob** — typeglob; delegates slot access to global symbol table

Context tracking, auto-vivification, and string/number coercion implemented consistently across both backends.

---

## RuntimeScalar Internals

**Three key fields:**

1. **`int blessId`** — `0` = unblessed, `> 0` = blessed, `< 0` = blessed with overloads (fast overload check)

2. **`int type`** — `INTEGER`, `DOUBLE`, `STRING`, `UNDEF`, `BOOLEAN`, `TIED_SCALAR`, `DUALVAR`, plus reference types (high bit set): `CODE`, `ARRAYREFERENCE`, `HASHREFERENCE`, `REGEX`, `GLOBREFERENCE`

3. **`Object value`** — the actual data; type field determines interpretation

**Overload optimization:** Negative `blessId` → class has overloads → check `OverloadContext`. Positive → skip expensive lookup (~10–20ns saved per operation). Detection happens at `bless` time.

---

## RuntimeScalar in Action

```perl
my $x = "10";
my $y = $x + 5;        # String → number: 15
my $z = $x . " cats";  # Number → string: "10 cats"
```

**Context-aware type coercion** — the same variable is both string and number.

```perl
my @arr;
$arr[10] = "x";        # Auto-expands to 11 elements
my $count = @arr;      # Scalar context: returns length
```

**Auto-vivification** — `RuntimeArray` auto-expands on assignment.

---

## Unicode and String Handling

**Challenge:** Java UTF-16 vs Perl's flexible encoding

```perl
my $emoji = "👍";  # U+1F44D
say length($emoji);  # Must return 1, not 2
say ord($emoji);     # Must return 128077
```

Java uses 2 UTF-16 code units for emoji; Perl counts code points.

**Solution:** `Character.toCodePoint()`, `codePointCount()`, and dual representation in pack/unpack.

---

## Control Flow: Non-Local Jumps

```perl
OUTER: for my $i (1..10) {
    INNER: for my $j (1..10) {
        last OUTER if $i * $j > 50;
        next INNER if $j == 5;
    }
}
```

**Implementation:** Tagged return values (`RuntimeControlFlowList`).

- Returns carry a control-flow tag (last/next/redo/goto/tail-call) + target label
- Caller checks tag and dispatches accordingly
- Untagged lists → normal execution continues
- Implements `goto &sub` (tail calls) and non-local jumps safely

---

## Control Flow: Why It's Hard

**Technical approaches combined:**

1. Block-level dispatchers with label tracking
2. `die` → Java exceptions; `eval` → try-catch blocks
3. Proper scope boundaries
4. **Tagged return values** for cross-boundary control flow

**Why register architecture helps:** Stack state would corrupt on non-local jumps. Registers maintain state explicitly. Label → bytecode offset mapping with shared handlers for multiple exits.

---

## Closures and Lexical Scoping

```perl
sub make_counter {
    my $count = 0;
    return sub { return ++$count };
}
my $c = make_counter();
say $c->();  # 1
say $c->();  # 2
```

**JVM backend:** Each anonymous sub → new JVM class. All visible lexicals passed as constructor arguments. Captured variables shared by Java reference — mutations visible to both scopes.

**Internal VM:** Dedicated opcode for closure variable allocation, same runtime objects.

---

## Context Propagation and `wantarray`

```perl
sub flexible {
    return wantarray ? (1, 2, 3) : 42;
}
my @arr = flexible();   # list context → (1, 2, 3)
my $n   = flexible();   # scalar context → 42
```

Context threaded through the entire call stack as `EmitterContext` during code generation. At each call site, the compiler emits the correct context flag. `wantarray` reads this flag at runtime. Works in both backends.

---

## `local` and Dynamic Scoping

```perl
our $x = "global";
sub inner { say $x }

sub outer {
    local $x = "dynamic";
    inner();               # prints "dynamic"
}
inner();  # "global"
outer();  # "dynamic"
inner();  # "global" again
```

Saves the current value onto a save stack; restores on scope exit — even through `die`/`eval`. Implemented for scalars, arrays, hashes, typeglobs, and filehandles.

---

## `tie`: Attaching Behavior to Variables

```perl
package MyScalar;
sub TIESCALAR { bless { val => $_[1] }, $_[0] }
sub FETCH     { say "reading!"; $_[0]{val} }
sub STORE     { say "writing!"; $_[0]{val} = $_[1] }

package main;
tie my $x, 'MyScalar', 42;
$x = 10;   # prints "writing!"
say $x;    # prints "reading!" then 10
```

`TIED_SCALAR` type in `RuntimeScalar` dispatches `FETCH`/`STORE` transparently. Supported for scalars, arrays, hashes, and filehandles.

---

## Regular Expressions

**Architecture:**
- Uses **Java's regex engine** (`java.util.regex.Pattern`)
- **RegexPreprocessor** translates Perl syntax → Java syntax
- **RuntimeRegex** manages matching, captures, special variables

**Compatibility layer handles:**
- Octal escapes, named Unicode (`\N{name}`)
- Character classes: `[:ascii:]` → `\p{ASCII}`
- Multi-character case folds: `ß` → `(?:ß|ss|SS)`
- Modifiers: `/i`, `/g`, `/x`, `/xx`, `/e`, `/ee`

Regex cache (1000 patterns). Unsupported: recursive patterns, variable-length lookbehind.

---

## Typeglobs: Emulation Strategy

**Perl:** Single symbol with multiple slots. **PerlOnJava:** No materialized globs.

**Separate global maps** for each slot type:
- `globalVariables` → SCALAR, `globalArrays` → ARRAY
- `globalHashes` → HASH, `globalCodeRefs` → CODE
- `globalIORefs` → IO, `globalFormatRefs` → FORMAT

**Glob assignment creates aliases** by sharing map entries:
```java
// *foo = *bar creates aliases
globalArrays.put("foo", globalArrays.get("bar"));  // Same object
```

**Slot access:** `*foo{CODE}` → `GlobalVariable.getGlobalCodeRef("foo")`

Lazy initialization — only allocated slots consume memory. Perl glob semantics without JVM Gv structures.

---

## Module Loading and XSLoader

**`require` mechanism:**
1. Converts `Module::Name` → `Module/Name.pm`
2. Searches `@INC`; executes once; caches in `%INC`

**300+ bundled modules live inside the JAR:**
```
%INC: 'Data/Dumper.pm' =>
  'file:/path/to/perlonjava.jar!/lib/Data/Dumper.pm'
```

**XSLoader:** Loads Java extensions instead of C.
- No C compiler needed
- JNA (Java Native Access) replaces XS for native libraries
- Java equivalents easier to write and maintain than C/XS

---

# Section 4: Integration & Future

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

## JVM Ecosystem and Future Targets

**Current:** Standard JVM (HotSpot) — Oracle JDK, OpenJDK

**Future targets:**

1. **GraalVM** — native image compilation
   - Standalone native executables, instant startup, smaller footprint

2. **Android DEX** — mobile platform
   - Convert JVM bytecode → Dalvik bytecode
   - Run Perl on Android devices

**The Internal VM is the key to multi-platform support.** Custom bytecode is platform-independent and portable to any JVM derivative. This is why the dual backend matters beyond performance.

---

## Current Limitations

**JVM-incompatible:**
- `fork` — not available on JVM
- `DESTROY` — Perl uses reference counting; JVM uses non-deterministic GC
- Threading — not yet implemented
- XS modules — use Java equivalents

**Partially implemented:**
- Some regex features (recursive patterns, variable-length lookbehind)
- Taint checks

**Workarounds:** JNA for native access, Java threading APIs, file auto-close at exit.

---

## Current Status and Roadmap

**Recently Completed (v5.42.3):**
- JVM Compiler backend stable for many workloads
- Full Perl class features
- System V IPC, socket operations

**Active Development:**
- Internal VM performance refinement
- Automatic fallback for large methods
- Optimizing eval STRING compilation

**Future Plans:**
- Replace regex engine with one more compatible with Perl
- Add a Perl-like single-step debugger

---

# Closing

---

## Why This Matters

**PerlOnJava demonstrates:**

- Compiler engineering at scale (392 files, 260K tests)
- **Test-Driven Development** — no formal Perl spec; tests define behavior
- Creative solutions to hard problems
- Language interoperability on the JVM
- Sustained engineering effort (10+ years, 5,741 commits)

**Bringing Perl's expressiveness to the JVM's platform reach.**

---

## How to Get Involved

**Project:**
- GitHub: github.com/fglock/PerlOnJava
- License: Artistic 2.0 (same as Perl)

**Ways to contribute:**
1. **Testing** — run your scripts, report issues
2. **Module porting** — help with CPAN modules
3. **Documentation** — improve guides
4. **Core development** — implement features
5. **Performance** — identify bottlenecks

---

## Conclusion

- **Production-oriented** Perl compiler for JVM (actively evolving)
- **2.2× faster** than Perl 5 on loop benchmarks (JVM backend)
- **260,000+ tests** in the suite
- **Key integrations:** JDBC, JSR-223, Maven
- **Dual backend** — performance + flexibility

**Proving that language ecosystems can be bridged.**

---

## Questions?

**Contact:**
- GitHub: github.com/fglock/PerlOnJava
- Issues: github.com/fglock/PerlOnJava/issues

**Thank you!**

---

## Acknowledgments

**Special thanks to:**

- **Larry Wall** — for creating Perl and its philosophy
- **Perl test writers** — 260,000+ tests that made this project possible
  - Without formal specification, these tests define Perl's behavior
- **Perl community** — for decades of innovation and support
- **Prior Perl-on-JVM pioneers** — JPL, perljvm, Perlito5

This project stands on the shoulders of giants.

---
