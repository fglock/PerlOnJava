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
- Builds OP tree → peephole optimizations → runs

**PerlOnJava (dual backend):**
```
Perl Source → Compiler → JVM Bytecode → JVM Execution
                       ↘ Custom Bytecode → Internal VM
```

Shared frontend + shared runtime, two execution paths.

Note:
Why not transpile to Java source? Perlito5 (predecessor) compiled Perl → Java → bytecode. Efficient, but slower startup and `eval STRING` invokes the Java compiler at runtime. PerlOnJava generates bytecode directly.

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

```perl
my @data;
BEGIN {
    @data = qw(a b c);  # Runs during compilation
    say "Compiling...";
}
say @data;  # Uses data set by BEGIN
```

Parser encounters `BEGIN` → wraps as anonymous sub → **executes immediately** → captures lexical variables.

`use Module` is sugar for `BEGIN { require Module; Module->import() }`

Note:
Other special blocks: END runs at program exit, INIT runs after compilation before runtime, CHECK runs after compilation in reverse order, UNITCHECK runs after each compilation unit. Behavior follows the Perl test suite; some edge cases not yet implemented.

---

## BEGIN: The Hard Part

```perl
my @data;
BEGIN { @data = qw(a b c) }  # Compile time
say @data;                    # Runtime — must see "a b c"
```

`@data` is a **lexical** — but BEGIN runs at compile time, before runtime lexicals exist.

**Solution:** Temporary package globals as a bridge:

1. Parser **snapshots** all visible lexicals
2. Each `my` variable gets a temporary global: `PerlOnJava::_BEGIN_42::data`
3. BEGIN body is compiled with `our` aliases to these globals
4. BEGIN **executes immediately** — sets the global
5. At runtime, `my @data` **retrieves** the value from the global and removes it

Note:
This is implemented in SpecialBlockParser (capture + alias) and PersistentVariable (retrieve + cleanup). For eval STRING, the runtime values are passed via ThreadLocal storage so BEGIN blocks inside eval can access the caller's lexicals. The temporary globals are cleaned up after retrieval to avoid leaks.

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

Each validates at compile time and generates optimized bytecode.

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

*Why would you need two execution engines?*

---

## Why Two Backends?

JVM bytecode is the primary path — but three problems need a second backend:

1. **64KB method size limit** — large Perl subs exceed it
2. **CPU cache pressure** — sparse JVM bytecode overflows instruction caches
3. **ClassLoader overhead** — `eval STRING` pays per-class cost

The Internal VM solves all three.

Note:
This is a common VM design pattern: HotSpot, V8, SpiderMonkey, CRuby all use tiered execution for similar reasons.

---

## JVM Backend: The Fast Path

**Loop increment, 1 billion iterations:**
- Perl 5: **7.88s**
- PerlOnJava: **3.57s** (2.2× faster)

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

Switch-based VM with ~200 opcodes.

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
- **Register-based:** Explicit operands (`rd = rs1 op rs2`) maintain correctness regardless of control flow

Perl's complex control flow — labeled loops, `goto`, `eval` — requires register architecture.

---

## eval STRING: Avoiding the ClassLoader

**1,000,000 unique eval strings:**

| Implementation | Time |
|----------------|------|
| Perl 5 | 1.29s |
| PerlOnJava (Internal VM) | 2.78s |

The Internal VM avoids ClassLoader overhead — each eval compiles directly to register bytecode without generating a JVM class.

Note:
Set JPERL_EVAL_USE_INTERPRETER=1. Each iteration evals a different string, so compilation overhead dominates. For typical eval usage with repeated patterns, performance is much closer.

---

# Section 3: Complex Language Features

*What makes Perl the hardest language to implement on the JVM?*

---

## Runtime Data Structures

**Five core classes — shared by both backends:**

1. **RuntimeScalar** — dynamically typed value
2. **RuntimeArray** — dynamic list with autovivification
3. **RuntimeHash** — associative array
4. **RuntimeCode** — compiled sub (`MethodHandle` or `InterpretedCode`)
5. **RuntimeGlob** — typeglob with slot delegation

Note:
RuntimeScalar supports: integer, double, string, reference, undef, regex, glob, tied, dualvar. RuntimeArray and RuntimeHash support plain, autovivifying, tied, and read-only modes. Context tracking, auto-vivification, and string/number coercion are implemented consistently across both backends.

---

## RuntimeScalar: Three Fields

1. **`int blessId`** — `0` unblessed, `> 0` blessed, `< 0` has overloads
2. **`int type`** — `INTEGER`, `DOUBLE`, `STRING`, `UNDEF`, references…
3. **`Object value`** — the actual data

Type field determines how to interpret the value.

Note:
Full type list: INTEGER, DOUBLE, STRING, UNDEF, BOOLEAN, TIED_SCALAR, DUALVAR, plus reference types with high bit set: CODE, ARRAYREFERENCE, HASHREFERENCE, REGEX, GLOBREFERENCE.

---

## RuntimeScalar: Overload Optimization

**Negative `blessId`** → class has overloads → check `OverloadContext`

**Positive `blessId`** → skip expensive lookup

~10–20ns saved per operation. Detection happens once at `bless` time.

Note:
This is a critical optimization because overload checks happen on nearly every operation on blessed references.

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
sub skip { last SKIP }   # Jumps out of caller's block!

SKIP: {
    skip() unless $have_feature;
    ok(1, "feature works");
}
```

`skip()` executes `last SKIP` — but `SKIP` is in the **caller's** scope.

**Implementation:** Tagged return values (`RuntimeControlFlowList`).

- Return carries a control-flow tag (`last`/`next`/`redo`/`goto`) + target label
- Caller checks tag and dispatches — unwinding across subroutine boundaries

---

## Control Flow: Why It's Hard

1. Block-level dispatchers with label tracking
2. `die` → Java exceptions; `eval` → try-catch
3. **Tagged return values** for cross-boundary jumps

Register architecture is essential — stack state would corrupt on non-local jumps.

Note:
Registers maintain state explicitly. Label → bytecode offset mapping with shared handlers for multiple exits.

---

## `caller()` and Stack Traces: Reusing the JVM Stack

```perl
sub foo { print "Called from: ", (caller)[1], " line ", (caller)[2], "\n" }
```

**Key trick:** `caller()` creates a `new Throwable()` to capture the **native JVM stack** — zero overhead when not called.

**JVM backend:** Each Perl sub compiles to a JVM class. `ByteCodeSourceMapper` maps token indices back to Perl file/line/package at compile time.

**Internal VM:** `InterpreterState` maintains a parallel frame stack (ThreadLocal). `ExceptionFormatter` walks the JVM stack, matching `BytecodeInterpreter.execute()` frames to Perl-level info.

No shadow stack needed — the JVM does the bookkeeping for us.

Note:
ExceptionFormatter handles three frame types: JVM-compiled Perl subs (org.perlonjava.anon*), Internal VM frames (BytecodeInterpreter.execute), and compile-time frames (CallerStack for use/BEGIN). The same mechanism powers warn/die location messages.

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

## Variable Scoping: Four Declarations, Three Strategies

| Declaration | JVM Mapping |
|---|---|
| `my $x` | `new RuntimeScalar()` → **JVM local variable slot** (`ASTORE`) |
| `our $x` | Lexical **alias** to package global → same JVM slot |
| `state $x` | Persistent via `StateVariable` registry, keyed by `__SUB__` |
| `local $x` | **Save stack** — snapshot + restore on scope exit |

All four use JVM local variable slots for fast access. The difference is *lifetime* and *initialization*.

Note:
`my` creates a fresh RuntimeScalar per call. `our` fetches the existing global RuntimeScalar from GlobalVariable registry and stores it in a local slot — it's the same object, so mutations are visible globally. `state` uses a static registry keyed by the enclosing sub reference + variable name, so it persists across calls but is unique per closure clone. `local` uses DynamicVariableManager to push/pop state — the compiler inserts getLocalLevel() at block entry and popToLocalLevel() in a finally block.

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

Compiler wraps the scope with `getLocalLevel()` / `popToLocalLevel()` — restores even through `die`/`eval`. Works for scalars, arrays, hashes, typeglobs, and filehandles.

Note:
DynamicVariableManager maintains a Stack of saved states. pushLocalVariable() calls dynamicSaveState() which snapshots {type, value, blessId} and resets to undef. popToLocalLevel() calls dynamicRestoreState() for each saved variable. The compiler detects `local` usage at compile time (FindDeclarationVisitor) and only emits save/restore code for blocks that need it.

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

## Signal Handling: `%SIG` and `alarm`

```perl
$SIG{ALRM} = sub { die "timeout\n" };
alarm(5);
eval { long_operation() };
alarm(0);
say "Caught: $@" if $@;
```

**Challenge:** JVM has no POSIX signals. Timer threads can't safely run Perl handlers.

**Solution:** A signal queue with safe-point checking:

1. `alarm()` schedules a timer on a daemon thread
2. Timer **enqueues** signal + handler (thread-safe `ConcurrentLinkedQueue`)
3. Compiler inserts `checkPendingSignals()` at every **loop entry**
4. Check is a volatile boolean read (~2 CPU cycles) — zero cost when idle

Note:
The kill() operator also uses this mechanism for self-signals. On Unix, external signals use JNA to call POSIX kill(). On Windows, signals map to GenerateConsoleCtrlEvent and TerminateProcess. The signal queue pattern ensures handlers always execute in the original thread context, not the timer thread.

---

## Regular Expressions

Uses **Java's regex engine** with a Perl compatibility layer:

- **RegexPreprocessor** translates Perl syntax → Java syntax
- Character classes: `[:ascii:]` → `\p{ASCII}`
- Case folds: `ß` → `(?:ß|ss|SS)`
- All modifiers: `/i`, `/g`, `/x`, `/xx`, `/e`, `/ee`

Cache of 1000 compiled patterns.

Note:
Also handles octal escapes, named Unicode (\N{name}), and surrogate pairs. Unsupported: recursive patterns, variable-length lookbehind. RuntimeRegex manages matching, captures, and special variables ($1, $&, etc.).

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

## Module Loading

`require` converts `Module::Name` → `Module/Name.pm`, searches `@INC`, caches in `%INC`.

**300+ modules bundled inside the JAR:**
```
%INC: 'Data/Dumper.pm' =>
  'file:/path/to/perlonjava.jar!/lib/Data/Dumper.pm'
```

---

## XSLoader: Java Instead of C

- Loads **Java extensions** instead of C shared libraries
- **JNA** (Java Native Access) replaces XS for native calls
- No C compiler needed

Note:
Java equivalents are easier to write and maintain than C/XS. The same API surface is exposed to Perl code.

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

## Future Targets

**Current:** Standard JVM (HotSpot)

1. **GraalVM** — native executables, instant startup
2. **Android DEX** — Perl on mobile devices

The Internal VM is the key — custom bytecode is platform-independent and portable to any JVM derivative.

Note:
This is why the dual backend matters beyond performance. GraalVM native image gives standalone executables with smaller footprint. Android DEX converts JVM bytecode to Dalvik bytecode.

---

## Current Limitations

**JVM-incompatible:**
- `fork` — not available on JVM
- `DESTROY` — JVM uses non-deterministic GC
- Threading — not yet implemented

**Partially implemented:**
- Some regex features, taint checks

Note:
Workarounds: JNA for native access, Java threading APIs, file auto-close at exit. XS modules use Java equivalents.

---

## Roadmap

**Stable now:** JVM backend, Perl class features, IPC, sockets

**In progress:** Internal VM optimization, eval STRING performance

**Next:** More compatible regex engine, single-step debugger

---

# Closing

---

## Perl Was Never Designed to Run on the JVM

We made it work anyway — and made it run fast**.

<span class="metric">260,000+ tests</span> · <span class="metric">392 files</span> · <span class="metric">5,741 commits</span>

No formal spec exists. The tests **are** the specification.

Note:
This is test-driven development at its most extreme — 260,000 tests define the language behavior.

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
- **Perl test writers** — 260,000+ tests that define Perl's behavior
- **Perl community** — for decades of innovation
- **Prior pioneers** — JPL, perljvm, Perlito5

**Questions?** → github.com/fglock/PerlOnJava/issues

---
