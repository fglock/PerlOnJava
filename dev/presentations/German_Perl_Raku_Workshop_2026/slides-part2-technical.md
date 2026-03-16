# PerlOnJava — Technical Deep-Dive

## From Perl Source to JVM Bytecode

German Perl/Raku Workshop 2026 — Flavio Glock

*Part 2: 40-minute technical talk — progressively more technical*

---

# Section 1: Compilation Pipeline

*How do you compile a language that wasn't designed to be compiled?*

---

## Compilation Approaches

**Perl 5 (traditional):**
```text
Perl Source → Lexer → Parser → OP Tree → Optimizer → Execution
```

**PerlOnJava (dual backend):**
```text
Perl Source → Lexer → Parser → Syntax Tree
  → JVM Bytecode → JVM Execution
  ↘ Custom Bytecode → Internal VM
```

Shared frontend + shared runtime, two execution paths.

Note:
Perlito5 compiled Perl → Java → bytecode. This worked, but `eval STRING` invoked the Java compiler at runtime. PerlOnJava generates bytecode directly — faster startup.

---

## Lexer: Tokenization

Breaks source into tokens: identifiers, operators, keywords.

```perl
my $café_123 = 42;        # Valid
my $𝕦𝕟𝕚𝕔𝕠𝕕𝕖 = "test";      # Surrogate pairs
```

**Challenge:** Characters beyond U+FFFF need surrogate pair handling.

**Solution:** IBM's ICU4J library for Unicode code points.

Note:
Perl limits identifiers to 251 code points; ICU4J handles code points rather than UTF-16 units.

---

## Parser: Building the Syntax Tree

**Three-layer architecture:**

1. **Recursive descent** (`StatementParser`, `ParseBlock`)
   - Control structures, declarations, subroutines, special blocks

2. **Precedence climbing** (`Parser.parseExpression`)
   - Operator precedence, associativity, ternary, method calls

3. **Operator-specific parsers** (20+ specialized)
   - `StringParser`, `PackParser`, `SignatureParser`

Modular AST: `BlockNode`, `BinaryOperatorNode`, `ListNode`
— easy to extend.

---

## Special Blocks: BEGIN, END, INIT, CHECK

```perl
my @data;
BEGIN {
    @data = qw(a b c);  # Runs during compilation
    say "Compiling...";
}
say @data;  # Uses data set by BEGIN
```

Parser encounters `BEGIN` → wraps as anonymous sub
→ **executes immediately** → captures lexical variables.

`use Module` is sugar for
`BEGIN { require Module; Module->import() }`

Note:
END runs at exit, INIT after compilation, CHECK in reverse order, UNITCHECK per compilation unit. Behavior follows the Perl test suite.

---

## BEGIN: The Hard Part

```perl
my @data;
BEGIN { @data = qw(a b c) }  # Compile time
say @data;                    # Runtime — must see "a b c"
```

`@data` is a **lexical** — but BEGIN runs at compile time,
before runtime lexicals exist.

**Solution:** Temporary package globals as a bridge:

1. Parser **snapshots** all visible lexicals
2. Each `my` variable gets a temporary global
3. BEGIN body compiled with `our` aliases
4. BEGIN **executes immediately** — sets the global
5. At runtime, `my @data` **retrieves** + removes it

Note:
Implemented in SpecialBlockParser (capture + alias) and PersistentVariable (retrieve + cleanup). eval STRING uses ThreadLocal storage for BEGIN access to caller lexicals. Temporary globals cleaned up after retrieval.

---

## EmitterVisitor: Bytecode Generation

**What it does:**
- Traverses AST → generates JVM bytecode via ASM library
- Manages symbol tables
- Context propagation (void/scalar/list)

**Key challenge:** JVM's **64KB method size limit** — large Perl subs can exceed it.

**Solution:** Automatic fallback to the Internal VM for oversized methods.

---

## Sublanguage Parsing

**Perl embeds multiple DSLs — each with its own parser:**

1. Regular expressions: `m/pattern/flags`
2. Pack/unpack templates: `pack("N*", @values)`
3. Sprintf format strings: `sprintf("%d", $n)`
4. Transliteration: `tr/a-z/A-Z/`
5. String interpolation: `"Value: $x\n"`

Each validates at compile time and generates optimized bytecode.

---

## Sublanguage Example: String Interpolation

```perl
my $name = "World";
my $msg = "Hello $name!\n";
```

**Generated AST** (from `./jperl --parse`):
```text
BinaryOperatorNode: =
  OperatorNode: my $msg
  BinaryOperatorNode: join
    ListNode:
      StringNode: 'Hello '
      OperatorNode: $name
      StringNode: '!\x0A'
```

Compile-time validation catches errors early.

---

# Section 2: Dual Execution Model

*Why would you need two execution engines?*

---

## Why Two Backends?

JVM bytecode is the primary path — but three problems need a second backend:

1. **64KB method size limit** — large Perl subs exceed it
2. **CPU cache pressure** — JVM bytecode overflows icache
3. **ClassLoader overhead** — `eval STRING` pays per-class cost

The Internal VM solves all three.

Note:
This is a common VM design pattern: HotSpot, V8, SpiderMonkey, CRuby all use multi-backend execution for similar reasons.

---

## JVM Backend: The Fast Path

**Loop increment, 1 billion iterations:**
- Perl 5: **7.88s**
- PerlOnJava: **3.57s** (2x faster)

After JIT warmup (~10K iterations), the JVM inlines and unrolls hot loops.

Note:
Pipeline: AST → ASM → JVM bytecode → ClassLoader. Best for long-running code with hot loops.

---

## JVM Tiered Compilation

**HotSpot progressively optimizes hot code:**

- **Tier 0:** Interpreter — collects profiling data
- **Tier 1–3:** C1 Compiler — fast compilation
- **Tier 4:** C2 Compiler — aggressive inlining, peak performance

**Key insight:** Keep critical paths ≤ 35 bytecodes for inlining.

This is why we split methods into fast/slow paths.

Note:
Default thresholds: MaxInlineSize=35, FreqInlineSize=325.

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

90%+ calls hit INTEGER → single-digit nanoseconds when inlined.

Note:
This pattern is used throughout: getDouble(), toString(), getBoolean().

---

## Custom Bytecode Backend: Compact and Flexible

- No method size limits
- Lower startup overhead — no class generation
- 65,536 virtual registers
- Good fit for `eval`-heavy scripts

Switch-based VM with ~300 opcodes.

Note:
Peak throughput is lower than the JVM backend, but startup is faster and there are no size constraints.

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
- **Register-based:** Explicit operands (`rd = rs1 op rs2`)
  — correct regardless of control flow

Perl's complex control flow — labeled loops, `goto`, `eval`
— requires register architecture.

---

## eval STRING: Avoiding the ClassLoader

**1,000,000 unique eval strings:**

| Implementation | Time |
|----------------|------|
| Perl 5 | 1.29s |
| PerlOnJava (Internal VM) | 2.78s |

Internal VM skips ClassLoader — compiles directly
to register bytecode, no JVM class generated.

Note:
Set JPERL_EVAL_USE_INTERPRETER=1. Each iteration evals a unique string, so compilation dominates. Repeated patterns perform much closer.

---

# Section 3: Complex Language Features

*What makes Perl the hardest language to implement on the JVM?*

---

## Runtime Data Structures

**Five core classes — shared by both backends:**

1. **RuntimeScalar** — dynamically typed value
2. **RuntimeArray** — dynamic list with autovivification
3. **RuntimeHash** — associative array
4. **RuntimeCode** — compiled sub (`MethodHandle`/`InterpretedCode`)
5. **RuntimeGlob** — typeglob with slot delegation

Note:
RuntimeScalar types: integer, double, string, reference, undef, regex, glob, tied, dualvar. Arrays/hashes support plain, autovivifying, tied, and read-only modes. Coercion and context consistent across both backends.

---

## RuntimeScalar: Three Fields

1. **`int blessId`** — `0` unblessed, `>0` blessed, `<0` overloads
2. **`int type`** — `INTEGER`, `DOUBLE`, `STRING`, `UNDEF`, references…
3. **`Object value`** — the actual data

Type field determines how to interpret the value.

Note:
Types: INTEGER, DOUBLE, STRING, UNDEF, BOOLEAN, TIED_SCALAR, DUALVAR. References (high bit set): CODE, ARRAYREFERENCE, HASHREFERENCE, REGEX, GLOBREFERENCE.

---

## RuntimeScalar: Overload Optimization

**Negative `blessId`** → class has overloads → check `OverloadContext`

**Positive `blessId`** → skip expensive lookup

~10–20ns saved per operation. Detection happens once at `bless` time.

Note:
Critical because overload checks happen on nearly every operation on blessed references.

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

**Solution:** `Character.toCodePoint()`, `codePointCount()`,
and dual representation in pack/unpack.

---

## Control Flow: Non-Local Jumps

```perl
sub skip { last SKIP }   # Jumps out of caller's block!

SKIP: {
    skip() unless $have_feature;
    ok(1, "feature works");
}
```

`skip()` executes `last SKIP` — but `SKIP` is in the **caller's** scope.

**Implementation:** Tagged return values (`RuntimeControlFlowList`).

- Return carries a control-flow tag + target label
- Caller checks tag and dispatches — unwinding across boundaries

---

## Control Flow: Why It's Hard

1. Block-level dispatchers with label tracking
2. `die` → Java exceptions; `eval` → try-catch
3. **Tagged return values** for cross-boundary jumps

Register architecture is essential
— stack state would corrupt on non-local jumps.

Note:
Registers maintain state explicitly. Label → bytecode offset mapping with shared handlers for multiple exits.

---

## `caller()` and Stack Traces: Reusing the JVM Stack

```perl
sub foo { print "Called from: ", (caller)[1], " line ", (caller)[2], "\n" }
```

`caller()` captures the **native JVM stack** via `new Throwable()`
— zero cost when unused.

- **JVM backend:** Each sub → JVM class.
  `ByteCodeSourceMapper` maps tokens to file/line/package.
- **Internal VM:** `InterpreterState` frame stack (ThreadLocal).
  `ExceptionFormatter` maps JVM → Perl-level info.

No shadow stack — the JVM does the bookkeeping.

Note:
ExceptionFormatter handles three frame types: JVM-compiled subs (anon*), Internal VM frames, and compile-time frames (CallerStack). Same mechanism powers warn/die messages.

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

- **JVM backend:** Each anon sub → new JVM class.
  Lexicals passed as constructor args, shared by reference.
- **Internal VM:** Dedicated opcode for closure variable allocation,
  same runtime objects.

---

## Context Propagation and `wantarray`

```perl
sub flexible {
    return wantarray ? (1, 2, 3) : 42;
}
my @arr = flexible();   # list context → (1, 2, 3)
my $n   = flexible();   # scalar context → 42
```

Compiler threads context via `EmitterContext`,
emitting the right flag at each call site.
`wantarray` reads it at runtime. Works in both backends.

---

## Variable Scoping: Four Declarations, Three Strategies

| Declaration | JVM Mapping |
|---|---|
| `my $x` | `new RuntimeScalar()` → **JVM local variable slot** (`ASTORE`) |
| `our $x` | Lexical **alias** to package global → same JVM slot |
| `state $x` | Persistent via `StateVariable` registry, keyed by `__SUB__` |
| `local $x` | **Save stack** — snapshot + restore on scope exit |

All four use JVM local variable slots for fast access.
The difference is *lifetime* and *initialization*.

Note:
`my` creates fresh RuntimeScalar per call. `our` aliases a global into a local slot — same object, mutations visible globally. `state` persists via registry keyed by sub ref + name, unique per closure clone. `local` uses DynamicVariableManager push/pop with getLocalLevel()/popToLocalLevel() in a finally block.

---

## `local`: Dynamic Scoping via Save Stack

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

Compiler wraps scope with `getLocalLevel()` / `popToLocalLevel()`
— restores even through `die`/`eval`.

Note:
DynamicVariableManager uses a Stack of saved states. pushLocalVariable() snapshots {type, value, blessId} and resets to undef. popToLocalLevel() restores each variable. Compiler detects `local` at compile time and only emits save/restore for blocks that need it.

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

`TIED_SCALAR` type dispatches `FETCH`/`STORE` transparently.
Works for scalars, arrays, hashes, and filehandles.

---

## Signal Handling: `%SIG` and `alarm`

```perl
$SIG{ALRM} = sub { die "timeout\n" };
alarm(5);
eval { long_operation() };
alarm(0);
say "Caught: $@" if $@;
```

**Challenge:** JVM has no POSIX signals.

**Solution:** Signal queue with safe-point checking:

1. `alarm()` schedules a timer on a daemon thread
2. Timer **enqueues** signal + handler (thread-safe queue)
3. Compiler inserts `checkPendingSignals()` at loop entry
4. Volatile boolean read (~2 cycles) — zero cost when idle

Note:
kill() reuses this mechanism. Unix signals via jnr-posix; Windows via GenerateConsoleCtrlEvent/TerminateProcess. Handlers always execute in the original thread context.

---

## Regular Expressions

Uses **Java's regex engine** with a Perl compatibility layer:

- **RegexPreprocessor** translates Perl syntax → Java syntax
- Character classes: `[:ascii:]` → `\p{ASCII}`
- Case folds: `ß` → `(?:ß|ss|SS)`
- All modifiers: `/i`, `/g`, `/x`, `/xx`, `/e`, `/ee`

Cache of 1000 compiled patterns.

Note:
Also handles octal escapes, named Unicode (\N{name}), surrogate pairs. Unsupported: recursive patterns, variable-length lookbehind. RuntimeRegex manages captures and special variables.

---

## Typeglobs: No Materialized Globs

**Separate global maps** for each slot type:
- `globalVariables` → SCALAR, `globalArrays` → ARRAY
- `globalHashes` → HASH, `globalCodeRefs` → CODE

**Glob assignment creates aliases:**
```java
// *foo = *bar
globalArrays.put("foo", globalArrays.get("bar"));
```

Lazy initialization — only allocated slots consume memory.

Note:
Also: globalIORefs → IO, globalFormatRefs → FORMAT. Slot access: *foo{CODE} → GlobalVariable.getGlobalCodeRef("foo"). Full Perl glob semantics without JVM Gv structures.

---

## Key Takeaways

- **Dual backend** — JVM for performance, Internal VM for flexibility
- **Shared runtime** — both backends use the same data structures
- **JVM-friendly design** — fast/slow path splits enable JIT inlining
- **Perl's complexity mapped to JVM** — closures, scoping,
  non-local jumps, `eval`

Perl was never designed for the JVM
— but careful engineering makes it work.

---

## What's Next: Part 3

**Integration, tooling, and roadmap:**

- Module loading and XSLoader
- JSR-223: embedding Perl in Java
- Interactive debugger
- Future targets and roadmap

---
