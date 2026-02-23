# PerlOnJava

## Perl Running Natively on the JVM

German Perl/Raku Workshop 2026

Flavio Glock

*40-minute technical talk — starts accessible, gets progressively more technical*

---

## What is Perl?

- High-level, general-purpose programming language
- Developed by Larry Wall in 1987 (39 years ago)
- Text processing, sysadmin, web, databases
- Comprehensive module ecosystem (CPAN)
- "Makes easy things easy and hard things possible"
- Still widely used in enterprise, bioinformatics, finance, DevOps

---

## What is PerlOnJava?

<span class="metric">A Perl compiler and runtime for the JVM</span>

- Compiles Perl scripts to **native JVM bytecode** — same as Java, Kotlin, Scala
- Not an interpreter wrapping a Perl binary
- Targets Perl 5.42+ semantics; compatibility is driven by the Perl test suite (not all behavior is covered yet)
- Gains JVM benefits: cross-platform, Java library access, JVM tooling

**Targets:** Java 21+ (any JDK)

---

## Historical Context

**Building on 25+ years of Perl-on-JVM research:**

- **1997**: JPL (Java-Perl Library) - Brian Jepson
- **2001**: Bradley Kuhn's MS thesis on porting Perl to JVM
- **2002**: perljvm prototype using Perl's B compiler
- **2013-2023**: Perlito5 - Perl to Java/JavaScript compiler (same author)
- **2024**: PerlOnJava - Production-oriented compiler and runtime (active development)

Each prior attempt informed this implementation. PerlOnJava is the culmination of lessons learned.

---

## Why Does This Matter? — A Concrete Scenario

You have **50,000 lines of Perl** data-processing code. Your company is moving to a Java/Kubernetes stack.

**Without PerlOnJava:** Rewrite everything in Java, or maintain a separate Perl runtime.

**With PerlOnJava:**
- Run many existing Perl scripts unchanged on the JVM
- Access any JDBC database (PostgreSQL, MySQL, Oracle, SQLite) — no C drivers needed
- Package as a single JAR for deployment (future)
- Embed Perl scripting in Java apps via the standard JSR-223 scripting API
- Use JNA (Java Native Access) for platform-specific native libraries
- Deploy to Docker, Kubernetes, Debian packages — anywhere Java runs

---

## Getting Started

**Requirements:** Java 21+ (any JDK)

```bash
git clone https://github.com/fglock/PerlOnJava
cd PerlOnJava
make           # Build and run tests (~5 minutes)
./jperl -E 'say "Hello from PerlOnJava!"'
```

**Add Maven dependencies (e.g. a JDBC driver):**
```bash
./Configure.pl --search mysql-connector-java
# Downloads and adds to classpath automatically
```

**Pure-Perl CPAN modules** typically work as-is — copy them to your lib path.
**XS/C modules** need Java equivalents (many bundled already).
**Migration checklist:** run your test suite under `./jperl`, then replace XS modules or JVM-incompatible features.

---

## Key Achievements

- <span class="metric">~260,000 tests</span> in the suite (compatibility tracked via tests)
- <span class="metric">392 Java source files</span> (src/main/java)
- <span class="metric">341 bundled Perl module files</span> (src/main/perl/lib/*.pm)
- <span class="metric">5,741 commits</span> since June 2024

**Bundled modules:** DBI, HTTP::Tiny, JSON, YAML, Text::CSV, Digest::MD5, MIME::Base64, and more

Compatibility is validated primarily through tests — Perl has no formal specification; the test suite is the de facto spec.

---

## Recent Milestones

- Latest Perl `class` features (modern OOP)
- Dual execution backend (JVM compiler + Internal VM)
- System V IPC and socket operations
- 260,000+ tests in the suite

---

## Live Demo

```bash
# Simple hello world
./jperl -E 'say "Hello from PerlOnJava!"'

# Conway's Game of Life
./jperl examples/life.pl

# JSON processing
./jperl examples/json.pl

# Database access with DBI
./jperl examples/dbi.pl
```

---

## Live Demo: Database Integration

**DBI with JDBC (Java Database Connectivity) drivers:**

JDBC is the standard Java database API — like DBI, but for Java. PerlOnJava's DBI module speaks JDBC directly, so any JDBC driver works without compiling C-based DBD::* adapters.

```perl
use DBI;
my $dbh = DBI->connect(
    "dbi:SQLite:dbname=test.db",
    "", "",
    { RaiseError => 1 }
);

my $sth = $dbh->prepare("SELECT * FROM users");
$sth->execute();
while (my $row = $sth->fetchrow_hashref) {
    say "User: $row->{name}";
}
```

**Supports:** PostgreSQL, MySQL, Oracle, SQLite, H2, any JDBC driver

---

# Section 2: Compilation Pipeline

## From Perl to JVM Bytecode

---

## Key Concepts Before We Dive In

**AST (Abstract Syntax Tree):** A tree representation of source code structure. `my $x = 1 + 2` becomes a tree with an assignment node whose right child is an addition node. The compiler walks this tree to generate code.

**JVM Bytecode:** Low-level instructions for the Java Virtual Machine — the same format Java, Kotlin, and Scala compile to. The JVM's JIT (Just-In-Time) compiler turns hot bytecode into native machine code at runtime.

**ASM library:** A Java library for generating JVM bytecode programmatically. PerlOnJava uses it to emit bytecode directly, without going through Java source.

---

## Background: Compilation Approaches

**Perl 5 (traditional):**

- Builds OP tree
- Performs peephole optimizations
- Then runs

There isn’t a super clean phase separation in p5 internals — it’s more organically evolved.

**PerlOnJava (dual backend approach):**
```
Perl Source → Compiler → JVM Bytecode → JVM Execution (JVM backend)
                      ↘ Custom Bytecode → Bytecode engine (Internal VM)
```

A multi-backend compiler with a shared frontend + shared runtime.

**Code generation targets (backends): two execution engines**
- **JVM backend**: Generates native JVM bytecode using ASM library (primary)
- **Internal VM**: A size-optimized execution backend specialized for dynamic code. Compact and no size limits (fallback + fast eval path)

Both backends share 100% of the runtime APIs — user code doesn't know which is running.

**Why generate bytecode directly instead of transpiling to Java source?**

Perlito5 (the predecessor) compiled Perl → Java source → bytecode. It generates efficient code but startup tends to be slower, and `eval STRING` is slower because it invokes the Java compiler at runtime. PerlOnJava generates JVM bytecode directly via the ASM library.

---

## The Five-Stage Pipeline

```
1. Lexer (Tokenization)
   ↓
2. Parser (AST Construction)
   ↓
3. StringParser (Domain-Specific Languages)
   ↓
4. EmitterVisitor (Bytecode Generation)
   ↓
5. Custom ClassLoader (Runtime Loading)
```

Perl source becomes indistinguishable from Java bytecode at the end of this pipeline.

---

## Lexer: Tokenization

**Challenge:** Unicode identifier support

```perl
my $café_123 = 42;        # Valid
my $𝕦𝕟𝕚𝕔𝕠𝕕𝕖 = "test";      # Surrogate pairs
```

**Solution:**
- IBM's ICU4J library
- Unicode code points (not UTF-16 units)
- Proper surrogate pair handling
- Perl limits identifiers to 251 code points; characters beyond U+FFFF require surrogate pair handling

Breaks source into tokens: identifiers, operators, keywords.

---

## Parser: Building the AST

**Three-layer parsing architecture:**

1. **Recursive descent parser** (`StatementParser`, `ParseBlock`)
   - Handles control structures (if, while, for, foreach)
   - Package/class declarations, use/require statements
   - Subroutine definitions, special blocks (BEGIN, END)

2. **Precedence climbing parser** (`Parser.parseExpression`)
   - Operator precedence and associativity
   - Binary operators (arithmetic, logical, comparison)
   - Special cases: ternary operator (?:), method calls (->)
   - Subscripts, dereferencing

3. **Operator-specific parsers** (20+ specialized parsers)
   - `StringParser` - String interpolation, heredocs
   - `PackParser`/`UnpackParser` - Binary templates
   - `SprintfFormatParser` - Format strings
   - `NumberParser` - Numeric literals (hex, octal, binary)
   - `IdentifierParser`, `SignatureParser`, `FieldParser`

Produces AST nodes like `BlockNode`, `BinaryOperatorNode`, `ListNode`. Modular design makes it easy to add new operators and syntax.

---

## Special Blocks: BEGIN, END, INIT, CHECK

**BEGIN blocks execute at compile time:**

```perl
my @data;
BEGIN {
    @data = qw(a b c);  # Runs during compilation
    say "Compiling...";  # Prints immediately
}
say @data;  # Uses data set by BEGIN
```

**Execution mechanism:**
1. Parser encounters `BEGIN { ... }`
2. Wraps block as anonymous subroutine
3. **Executes immediately** during parsing
4. Captures lexical variables from outer scope
5. Can modify compilation environment

**Other special blocks:**
- **END** - Runs at program exit (saved for later)
- **INIT** - Runs after compilation, before runtime
- **CHECK** - Runs after compilation (reverse order)
- **UNITCHECK** - Runs after each compilation unit

`use Module` is syntactic sugar for `BEGIN { require Module; Module->import() }` — so `use strict` and `use warnings` execute at compile time.
Behavior follows what's covered by the Perl test suite; some edge cases are not yet implemented.

---

## EmitterVisitor: Bytecode Generation

**What it does:**
- Traverses AST → generates JVM bytecode
- Uses ASM library
- Manages symbol tables
- Context propagation (void/scalar/list)

**Key challenge:** The JVM imposes a **65,535-byte (~64KB) limit per method**. Large Perl subroutines can exceed this. Solution: automatic fallback to the custom bytecode backend for oversized methods.

---

## JVM Bytecode Backend

**Perl:** `my $x = 10; say $x`

**Generated JVM bytecode:**
```
LDC 10
INVOKESTATIC RuntimeScalarCache.getScalarInt(I)
ASTORE 7
NEW RuntimeScalar
DUP
INVOKESPECIAL RuntimeScalar.<init>()
ASTORE 25
ALOAD 25
ALOAD 7
SWAP
INVOKEVIRTUAL RuntimeBase.addToScalar(RuntimeScalar)
...
INVOKESTATIC IOOperator.say(RuntimeList;RuntimeScalar)
```

**View with:** `./jperl --disassemble -E '...'`

Standard JVM bytecode — optimized by the JVM's JIT compiler at runtime.

---

## Custom Bytecode Backend

**Perl:** `my $x = 10; say $x`

**Generated Internal VM bytecode:**
```
Registers: 9
Bytecode length: 26 shorts

   0: LOAD_INT r4 = 10
   4: MOVE r3 = r4
   7: CREATE_LIST r6 = []
  10: SELECT r5 = select(r6)
  13: CREATE_LIST r7 = [r3]
  17: SAY r7, fh=r5
  20: LOAD_INT r8 = 1
  24: RETURN r8
```

**View with:** `./jperl --interpreter --disassemble -E '...'`

Custom register-based bytecode — much more compact than JVM bytecode. Explicit register operands (r3, r4, etc.), ~200 custom opcodes.

---

# Section 3: Dual Execution Model

## JVM Bytecode vs Custom Bytecode

Key innovation in PerlOnJava: two execution pathways that share 100% of the runtime. User code doesn't know which is running.

---

## Why Two Bytecode Backends?

**JVM bytecode is a good choice for most code:**
- Maximum performance via JIT optimization
- Native JVM stack frames, inlining, loop optimizations

**But three problems push us toward a second backend:**

1. **65,535-byte (~64KB) method size limit** — large Perl subroutines can exceed it. The Internal VM has no such limit.

2. **CPU cache pressure** — very large JVM methods produce sparse bytecode that overflows instruction caches. The Internal VM's compact bytecode is more cache-efficient for those cases.

3. **ClassLoader bottleneck for `eval STRING`** — the JVM class loader has overhead per loaded class. The Internal VM avoids that overhead and reduces eval latency (exact speedup varies).

This is a common VM design pattern: many VMs (HotSpot, V8, SpiderMonkey, CRuby) use tiered execution for similar reasons.

---

## JVM Bytecode Backend: The Fast Path

**Performance example (loop increment with lexical variable, 1,000,000,000 iterations):**
- Perl 5: 7.88s baseline
- PerlOnJava JVM Backend: 3.57s (2.20x faster)

**Advantages:**
- High throughput after JIT warmup
- Native JVM stack frames
- JVM optimizations (inlining, loop optimizations)

**Limitations:**
- 65,535-byte (~64KB) method size limit
- Higher compilation overhead for dynamic eval

Pipeline: AST → ASM → JVM bytecode → ClassLoader. After warmup (order of 10K iterations), reaches peak performance. Best for long-running code.

---

## JVM Tiered Compilation

**HotSpot JVM progressively optimizes hot code through 5 tiers:**

**Tier 0: Interpreter** - Initial execution
- Bytecode interpretation, no compilation
- Collects profiling data (method calls, branch frequencies)

**Tier 1-3: C1 Compiler** (Client compiler, fast compilation)
- Tier 1: Simple C1 compilation, no profiling
- Tier 2: Limited C1 with invocation counters
- Tier 3: Full C1 with profiling for C2

**Tier 4: C2 Compiler** (Server compiler, max optimization)
- Aggressive inlining, loop optimizations
- Uses profiling data from Tier 3
- Peak performance, but slow to compile

**Strategy:** Keep critical paths small (≤ 35 bytecodes) for aggressive inlining

Default in Java 8+: `-XX:+TieredCompilation`. Hot methods progress: Tier 0 → Tier 3 → Tier 4. Inlining thresholds: `-XX:MaxInlineSize=35`, `-XX:FreqInlineSize=325`. This is why PerlOnJava splits methods into fast/slow paths.

---

## Optimization Example: RuntimeScalar.getInt()

**Hot path (≤ 35 bytecodes):**
```java
// Inlineable fast path for getInt()
public int getInt() {
    if (type == INTEGER) {
        return (int) this.value;  // ~2ns
    }
    return getIntLarge();  // Call slow path
}
```

**Cold/Slow path (> 35 bytecodes):**
```java
// Slow path for getInt()
private int getIntLarge() {
    return switch (type) {
        case DOUBLE -> (int) ((double) value);
        case STRING -> NumberParser.parseNumber(this).getInt();
        case TIED_SCALAR -> this.tiedFetch().getInt();
        case DUALVAR -> ((DualVar) value).numericValue().getInt();
        default -> Overload.numify(this).getInt();
    };
}
```

**Result:** Common case (INTEGER) is inlined, rare cases don't bloat hot path

90%+ of `getInt()` calls are on INTEGER type. Fast path: single-digit nanoseconds when inlined. Slow path: tens to hundreds of nanoseconds. This pattern is used throughout: `getDouble()`, `toString()`, `getBoolean()`.

---

## Custom Bytecode Backend: Compact and Flexible

**Performance characteristics:**
- Lower startup overhead — no JVM class generation
- Peak throughput is generally lower than the JVM backend (numbers vary)
- Good fit for eval-heavy scripts where classloading dominates

**Advantages:**
- No method size limits
- Compact bytecode footprint
- 65,536 virtual registers

**Architecture:** Register-based, not stack-based (explained on the next slide)

Switch-based bytecode VM. ~200 opcodes covering all Perl operations.

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

- **Stack-based:** Stack state becomes inconsistent on non-local jumps (`goto`, `last LABEL`)
- **Register-based:** Explicit operands (`rd = rs1 op rs2`) maintain correctness regardless of control flow

Perl's complex control flow — labeled loops, `goto`, `eval` — requires register architecture.

---

## JVM Ecosystem

**Current support: Standard JVM (HotSpot)**
- Oracle JDK, OpenJDK
- Compiler mode generates standard JVM bytecode
- Runs on any compliant JVM

**Future targets:**

1. **GraalVM** - Native image compilation
   - Compile to standalone native executables
   - Instant startup, no JVM warmup
   - Smaller footprint

2. **Android DEX** - Mobile platform
   - Convert JVM bytecode → Dalvik bytecode
   - Run Perl on Android devices

**Key advantage: Internal VM enables portability**
- Custom bytecode format is platform-independent
- Can be ported to any JVM derivative
- Compiler mode ties us to standard JVM bytecode

The Internal VM is the key to multi-platform support. GraalVM and DEX support are planned for future releases. This is why the dual backend matters beyond just performance.

---

## eval STRING Performance

**Dynamic eval (1,000,000 unique strings, JPERL_EVAL_USE_INTERPRETER=1):**

| Implementation | Time | vs Perl 5 |
|----------------|------|-----------|
| Perl 5 | 1.29s | baseline |
| PerlOnJava | 2.78s | 2.16x slower |

PerlOnJava can use the internal VM for `eval STRING` to avoid classloader overhead (enabled here via `JPERL_EVAL_USE_INTERPRETER=1`). Each iteration evals a different string —
- Compilation overhead dominates runtime
- Still functional for typical eval usage patterns

---

# Section 4: Complex Language Features

Now that we've seen *how* code runs, let's look at the hardest parts of making Perl *semantics* work correctly on the JVM.

---

## Unicode and String Handling

**Challenge:** Java UTF-16 vs Perl's flexible encoding

**Java:** UTF-16, surrogate pairs for U+10000+

**Perl:** Bytes or code points, dual representation

```perl
my $emoji = "👍";  # U+1F44D
say length($emoji);  # Must return 1, not 2
say ord($emoji);     # Must return 128077
```

Java uses 2 UTF-16 code units for emoji; Perl counts code points, not units. Solution: `Character.toCodePoint()`, `codePointCount()`, and dual representation in pack/unpack.

---

## Control Flow: Non-Local Jumps

```perl
OUTER: for my $i (1..10) {
    INNER: for my $j (1..10) {
        last OUTER if $i * $j > 50;  # Jump out 2 levels
        next INNER if $j == 5;        # Skip iteration
        redo INNER if condition();    # Restart iteration
    }
}

sub foo {
    goto ELSEWHERE if $error;  # Non-local goto
}
ELSEWHERE: print "Jumped!\n";
```

- We implement these using **tagged return values** (`RuntimeControlFlowList`) that carry control-flow markers across subroutine boundaries

---

## Control Flow: Solutions

**Technical approaches:**

1. Block-level dispatchers
2. Label tracking in symbol table
3. `die` → Java exceptions
4. `eval` → try-catch blocks
5. Proper scope boundaries
6. **Tagged return values** for control flow
    - Return `RuntimeControlFlowList` (tagged list) instead of regular `RuntimeList`
    - Tag contains control flow type (last/next/redo/goto/tail-call) and target label
    - Caller checks tag and dispatches accordingly
    - If list is not tagged, program continues normally
    - Implements Perl tail calls (`goto &sub`) and non-local jumps safely

**Why register architecture helps:** Stack state would corrupt on non-local jumps. Register-based maintains state explicitly. Label → bytecode offset mapping with shared handlers for multiple exits.

---

## Sublanguage Parsing

**Perl embeds multiple DSLs:**

1. Regular expressions: `m/pattern/flags`
2. Pack/unpack templates: `pack("N*", @values)`
3. Sprintf format strings: `sprintf("%d", $n)`
4. Transliteration: `tr/a-z/A-Z/`
5. String interpolation: `"Value: $x\n"`

**Architecture:** Each DSL has its own dedicated parser

- Parses DSL syntax into AST nodes
- Validates at compile-time (catches errors early)
- Generates optimized bytecode
- Provides consistent error messages

String interpolation parser breaks `"$x\n"` into literals, variables, escapes. Regex parser translates Perl syntax to Java regex at compile time. Pack/unpack parser validates templates and generates format handlers. Each parser is invoked by the main parser when context requires it.

---

## Sublanguage Example

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

Compile-time validation catches errors early and can optimize away unnecessary operations.

---

## Regular Expressions: Java Engine + Compatibility Layer

**Architecture:**
- Uses **Java's regex engine** (`java.util.regex.Pattern`)
- **RegexPreprocessor** translates Perl syntax → Java syntax
- **RuntimeRegex** manages matching, captures, and special variables

**Compatibility layer handles:**
- Octal escapes: `\120` → `\0120`
- Named Unicode: `\N{name}`, `\N{U+263D}`
- Character classes: `[:ascii:]` → `\p{ASCII}`
- Multi-character case folds: `ß` → `(?:ß|ss|SS)`
- Perl modifiers: `/i`, `/g`, `/x`, `/xx`, `/e`, `/ee`

**Result:** Many Perl regex features work with the compatibility layer

Regex cache (1000 patterns) for performance. Unsupported: recursive patterns, variable-length lookbehind. See `feature-matrix.md` for complete details.

---

## Runtime Data Structures

**Core classes:**

1. **RuntimeScalar** - Context-aware string/number/reference
2. **RuntimeArray** - Auto-vivification, slicing, context
3. **RuntimeHash** - Lazy init, ordered keys
4. **RuntimeCode** - Code refs with closures

**Key:** Perl semantics on JVM objects. All shared between JVM compiler and Internal VM. Context tracking, auto-vivification, truthiness, and string/number coercion are implemented consistently across both backends.

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

**Implementation:**
- `VariableCaptureAnalyzer` identifies which lexical variables each sub closes over at compile time
- Captured variables are stored in a shared cell (a reference-counted box)
- The `CREATE_CLOSURE_VAR` opcode allocates these cells at closure creation time
- Both the outer scope and the inner sub hold a reference to the same cell — mutations are visible to both
- Works identically in both the JVM backend and the Internal VM

---

## Context Propagation and `wantarray`

Perl has three calling contexts: **void**, **scalar**, and **list**. The called function can query which context it was called in:

```perl
sub flexible {
    if (wantarray) {
        return (1, 2, 3);   # list context
    } else {
        return 42;           # scalar context
    }
}
my @arr = flexible();   # list context → (1, 2, 3)
my $n   = flexible();   # scalar context → 42
```

Context is threaded through the entire call stack as an `EmitterContext` parameter during code generation. At each call site, the compiler emits the correct context flag. `wantarray` reads this flag at runtime. Implemented in both backends.

---

## `local` and Dynamic Scoping

```perl
our $x = "global";
sub inner { say $x }      # prints whatever $x is dynamically

sub outer {
    local $x = "dynamic"; # saves old value, restores on scope exit
    inner();               # prints "dynamic"
}
inner();  # prints "global"
outer();  # prints "dynamic"
inner();  # prints "global" again
```

`local` is implemented for scalars, arrays, hashes, typeglobs, and filehandles. It saves the current value of a global variable onto a save stack and restores it when the enclosing scope exits — even through `die`/`eval`.

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

Tied scalars, arrays, hashes, and filehandles are supported for core behaviors. The `TIED_SCALAR` type in `RuntimeScalar` dispatches `FETCH`/`STORE` calls to the tied class transparently.

---

## Module Loading: `require` and `@INC`

**How `require` works:**
1. Converts `Module::Name` → `Module/Name.pm`
2. Searches `@INC` for the file
3. Executes it once; caches path in `%INC`

**PerlOnJava's `@INC`:**
```
@INC = ('jar:PERL5LIB');
```

The **300+ bundled Perl modules** live *inside the JAR file* itself:
```
%INC: 'Data/Dumper.pm' =>
  'file:/path/to/perlonjava-3.0.0.jar!/lib/Data/Dumper.pm'
```

**Pure-Perl CPAN modules** typically work as-is — add their path to `PERL5LIB` or use `-I`. **XS modules** need Java equivalents (many are bundled already).

---

## RuntimeScalar Internals

**Three key fields:**

1. **`int blessId`** (inherited from `RuntimeBase`)
   - `0` = unblessed scalar
   - `> 0` = blessed object (normal class)
   - `< 0` = blessed object with overloads (enables fast overload check)

2. **`int type`** (from `RuntimeScalarType`)
   - Simple types: `INTEGER`, `DOUBLE`, `STRING`, `UNDEF`, `BOOLEAN`
   - Special types: `VSTRING`, `BYTE_STRING`, `TIED_SCALAR`, `DUALVAR`
   - Reference types (high bit set): `CODE`, `ARRAYREFERENCE`, `HASHREFERENCE`, `REFERENCE`, `REGEX`, `GLOBREFERENCE`

3. **`Object value`**
   - Stores the actual data (Integer, Double, String, RuntimeArray, RuntimeHash, etc.)
   - Type field determines how to interpret the value

**Overload optimization:**
- Negative `blessId` → class has overloads → check `OverloadContext`
- Positive `blessId` → no overloads → skip expensive lookup (~10-20ns saved per operation)

Dynamic typing: type can change at runtime. Reference bit (0x8000) distinguishes references from simple values. Overload detection happens at `bless` time, cached in `blessId` sign.

---

## RuntimeScalar Example

```perl
my $x = "10";
my $y = $x + 5;        # String → number: 15
my $z = $x . " cats";  # Number → string: "10 cats"
```

**Context-aware type coercion**

```perl
my @arr;
$arr[10] = "x";        # Auto-expands to 11 elements
my $count = @arr;      # Scalar context: returns length
```

**Auto-vivification**

The same variable is both string and number — context determines behavior. `RuntimeArray` auto-expands on assignment.

---

## Typeglobs: Emulation Strategy

**Perl typeglobs:** Single symbol with multiple "slots" (Gv)
```perl
*foo = *bar;           # Alias all slots
$foo{CODE}             # Access CODE slot
```

**PerlOnJava solution:**

1. **No materialized globs** - RuntimeGlob is just a name holder
2. **Separate global maps** for each slot type:
   - `globalVariables` → SCALAR slot
   - `globalArrays` → ARRAY slot
   - `globalHashes` → HASH slot
   - `globalCodeRefs` → CODE slot
   - `globalIORefs` → IO slot
   - `globalFormatRefs` → FORMAT slot

3. **Glob assignment creates aliases** by sharing map entries:
   ```java
   // *foo = *bar creates aliases
   globalArrays.put("foo", globalArrays.get("bar"));  // Same object
   globalHashes.put("foo", globalHashes.get("bar"));  // Same object
   ```

4. **Slot access** via `RuntimeGlob.getGlobSlot()`:
   ```perl
   *foo{CODE}  →  GlobalVariable.getGlobalCodeRef("foo")
   ```

**Result:** Perl glob semantics without JVM Gv structures

Aliasing works by sharing references in maps, not copying. `globalGlobs` tracks names that had glob assignments (for `CORE::GLOBAL` overrides). Lazy initialization: slots created on first access — only allocated slots consume memory.

---

## XSLoader: C vs Java

**Perl 5 XSLoader:**
- Loads C-based extensions (XS modules)
- Requires C compiler and platform-specific compilation
- Direct access to C libraries
- Examples: `DBI::DBD::*`, `JSON::XS`, `Compress::*`

**PerlOnJava XSLoader:**
- Loads Java-based extensions instead
- No C compiler needed
- Direct access to Java libraries and JVM ecosystem
- Examples: `DBI` (uses JDBC), native Java library bindings

**Key difference:** XS → JNA (Java Native Access) for native libraries

JNA is the Java equivalent of Perl's XS: it lets Java code call native C libraries without writing C. Perl 5 XS modules won't work directly, but Java equivalents with the same API can be created — easier to write and maintain than C/XS, with access to the entire Java ecosystem.

---

## Current Limitations

**JVM-incompatible features:**
-  `fork` - not available on JVM
-  `DESTROY` blocks - Perl uses reference counting for deterministic cleanup; JVM uses non-deterministic garbage collection
-  Threading - not yet implemented
-  Perl XS modules - C extensions don't work (use Java equivalents)

**Partially implemented:**
-  Some regex features (recursive patterns, variable-length lookbehind)
-  Warnings/strict behaviors implemented where covered by tests (edge cases remain)
-  Taint checks

**Workarounds available:**
- Use JNA for native library access instead of XS
- Use Java threading APIs instead of Perl threads
- File auto-close happens at program end

Most limitations are JVM-related, not design choices. Many CPAN modules are being ported to Java backend. See `feature-matrix.md` for complete details.

---

# Section 5: Integration & Future

---

## Java Ecosystem Integration

**1. JDBC Database Access (DBI)**

```perl
use DBI;
my $dbh = DBI->connect("jdbc:postgresql://localhost/mydb",
                       $user, $password);
my $sth = $dbh->prepare("SELECT * FROM users WHERE id = ?");
$sth->execute($user_id);
```

**Supports:** PostgreSQL, MySQL, Oracle, H2, SQLite, most JDBC drivers — no DBD::* adapters needed.

---

## JSR-223 Scripting API

JSR-223 is the standard Java scripting API (part of the JDK since Java 6). It lets any JVM language be embedded in Java applications via a common interface.

**Call Perl from Java:**

```java
ScriptEngineManager manager = new ScriptEngineManager();
ScriptEngine perl = manager.getEngineByName("perl");

perl.put("data", myJavaObject);
Object result = perl.eval("process_data($data)");
```

**Bidirectional:** Java ↔ Perl seamlessly. Use case: embed legacy Perl scripts in a modern Java application without rewriting them.

---

## Maven Dependency Management

```bash
./Configure.pl --search mysql-connector-java
# Automatically downloads JDBC drivers
# Adds to classpath
```

**Real use cases:**
- ETL pipelines (Perl text processing + JDBC)
- Embedded scripting in Java applications
- Cross-language integration

Manage dependencies with Maven — no manual JAR management. Integrate any Java library with Perl's expressive power.

---

## Current Status

**Recently Completed (v5.42.3):**
-  JVM Compiler backend stable for many workloads
-  Full Perl class features
-  System V IPC, socket operations

**Active Development:**
- Internal VM performance refinement
- Automatic fallback for large methods
- Optimizing eval STRING compilation
- 260,000+ tests in the suite

**Future Plans:**
- Replace regex engine with one more compatible with Perl
- Add a Perl-like single-step debugger

---

## How to Get Involved

**Project:**
- GitHub: github.com/fglock/PerlOnJava
- License: Artistic 2.0 (same as Perl)
- 5,741 commits since 2024

**Ways to contribute:**
1. Testing - run your scripts, report issues
2. Module porting - help with CPAN modules
3. Documentation - improve guides
4. Core development - implement features
5. Performance - identify bottlenecks

---

## Getting Started

```bash
git clone https://github.com/fglock/PerlOnJava
cd PerlOnJava
make           # Build and run tests
./jperl -E 'say "Hello from PerlOnJava!"'
```

**Resources:**
- Comprehensive documentation in `docs/`
- Tests as examples in `src/test/resources/unit/`
- Active development

---

## Why This Matters

**PerlOnJava demonstrates:**

- Compiler engineering at scale (392 files, 260k test suite)
- **Test-Driven Development**: No formal Perl specification exists; tests and CPAN code define behavior
- Creative solutions to hard problems
- Language interoperability
- Sustained engineering effort (10+ years)

**Bringing Perl's expressiveness to the JVM's platform reach**

Perl has no formal specification — behavior is defined by tests and the reference implementation. PerlOnJava's compatibility is validated primarily through its test suite.

---

## Conclusion

-  **Production-oriented** Perl compiler for JVM (actively evolving)
-  **Benchmark snapshot:** JVM backend 2.20x faster on loop increment; eval STRING 2.16x slower (numbers vary by workload)
-  **260,000+ tests** in the suite
-  **Key ecosystem integration** (JDBC, JSR-223)
-  **Active development** with public roadmap

**Proving language ecosystems can be bridged**

JVM bytecode backend: 2.20x faster than Perl 5 on loop benchmarks (current script run). Eval STRING remains slower than Perl 5 in this microbenchmark. Modern deployment options. Active, sustained development.

---

## Questions?

**Contact:**
- GitHub: github.com/fglock/PerlOnJava
- Issues: github.com/fglock/PerlOnJava/issues

**Thank you!**

---

## Acknowledgments

**Special thanks to:**

- **Larry Wall** - for creating Perl and its philosophy
- **Perl test writers** - for their comprehensive test suite that made this project possible
  - Without formal specification, these tests define Perl's behavior
  - 260,000+ tests are the foundation of PerlOnJava's compatibility
- **Perl community** - for decades of innovation and support
- **Prior Perl-on-JVM pioneers** - JPL, perljvm, Perlito5

This project stands on the shoulders of giants. Perl's test suite is remarkably comprehensive — test-driven development only works when the tests are this good.
