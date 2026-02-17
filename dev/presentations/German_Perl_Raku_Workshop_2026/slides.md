# PerlOnJava

## Perl Running Natively on the JVM

German Perl/Raku Workshop 2026

Flavio Glock

Note:
- Welcome everyone
- 40-minute technical talk
- First section useful for everyone, then progressively more technical
- Feel free to ask questions at the end

---

## What is Perl?

- High-level, general-purpose programming language
- 35+ years of development (created 1987)
- Text processing, sysadmin, web, databases
- Comprehensive module ecosystem (CPAN)
- "Makes easy things easy and hard things possible"

Note:
- For those unfamiliar with Perl
- Mature, battle-tested language
- Still widely used in enterprise, bioinformatics, finance, DevOps
- Powerful pattern matching and text processing

---

## What is PerlOnJava?

<span class="metric">A Perl compiler and runtime for the JVM</span>

- Compiles Perl scripts to **native JVM bytecode**
- Not just wrapping an interpreter
- Maintains Perl semantics
- Gains JVM benefits

Note:
- True compilation to bytecode
- Same as Java/Kotlin/Scala compilation
- Cross-platform, integrates with Java libraries
- Access to JVM tooling

---

## Why Does This Matter?

<div class="three-columns">
<div>

### Enterprise
- JDBC databases
- Maven dependencies
- JSR-223 scripting

</div>
<div>

### Cloud
- Docker containers
- Kubernetes
- Android (future)

</div>
<div>

### Performance
- **1.78x faster**
- JVM JIT optimization
- Competitive benchmarks

</div>
</div>

Note:
- Enterprise: Use Perl in Java-heavy environments
- Cloud: Modern deployment platforms
- Performance: Better than Perl 5 in many cases

---

## Key Achievements

- <span class="metric">250,000+ tests passing</span>
- <span class="metric">383 Java source files</span>
- <span class="metric">150+ core Perl modules</span>
- <span class="metric">341 Perl stdlib modules</span>
- <span class="metric">5,621+ commits</span>

Note:
- Demonstrates comprehensive Perl 5 compatibility
- Scale of implementation
- Active, sustained development since 2013
- Production-ready

---

## Recent Milestones

✅ Full Perl 5.38+ class features

✅ Dual execution backend (compiler + interpreter)

✅ System V IPC and socket operations

✅ 250,000+ tests passing

Note:
- Modern OOP with class keyword
- Two execution modes for flexibility
- Production-ready networking
- Comprehensive test coverage

---

## Performance Benchmark

**Loop Increment Test (100M iterations)**

| Implementation | Time | vs Perl 5 |
|----------------|------|-----------|
| Perl 5 | 1.53s | baseline |
| **PerlOnJava Compiler** | **0.86s** | **1.78x faster** ⚡ |
| PerlOnJava Interpreter | 1.80s | 0.85x (15% slower) |

Note:
- Real benchmark on numeric code
- Compiler mode significantly faster
- Interpreter still competitive
- JVM JIT optimization working well

---

## Live Demo

```bash
# Simple hello world
./jperl -E 'say "Hello from PerlOnJava!"'

# Conway's Game of Life
./jperl examples/life.pl

# JSON processing
./jperl examples/json.pl
```

Note:
- Quick demo if doing live
- Shows it's real and working
- Conway's Life is visually impressive

---

# Section 2: Compilation Pipeline

## From Perl to JVM Bytecode

Note:
- Now diving into technical details
- How we transform Perl source into executable bytecode
- Casual viewers: this is where it gets technical

---

## Background: JVM Compilation

**Traditional:**
```
Perl Source → Perl Interpreter → Execution
```

**PerlOnJava:**
```
Perl Source → Compiler → JVM Bytecode → JVM Execution
```

Note:
- Quick primer for mixed audience
- Compiler translates once, runs fast
- JVM is a "virtual computer" running bytecode
- Same bytecode works on all platforms

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

Note:
- True compiler architecture
- Not an interpreter
- Perl becomes indistinguishable from Java bytecode

---

## Lexer: Tokenization

**Challenge:** Unicode identifier support

```perl
my $café_123 = 42;        # Valid
my $𝕦𝕟𝕚𝕔𝕠𝕕𝕖 = "test";      # Surrogate pairs
```

**Solution:**
- IBM's ICU4J library
- 251 Unicode code points (not UTF-16 units)
- Proper surrogate pair handling

Note:
- Breaks source into tokens: identifiers, operators, keywords
- Perl limits identifiers to 251 code points
- Must handle characters beyond U+FFFF correctly
- ~50,000 lines per second

---

## Parser: Building the AST

**Challenge:** Context-aware parsing

- Same syntax, different meanings
- `<=>` can be spaceship operator OR qw() delimiter
- Complex precedence rules

**AST Node Types:**
- `BlockNode`, `BinaryOperatorNode`, `ListNode`
- `OperatorNode`, `MethodCallNode`

Note:
- Converts tokens to Abstract Syntax Tree
- Handles Perl's complex precedence
- Resolves ambiguities with lookahead
- Context tracking essential

---

## EmitterVisitor: Bytecode Generation

**What it does:**
- Traverses AST → generates JVM bytecode
- Uses ASM library
- Manages symbol tables
- Context propagation (void/scalar/list)

**Challenges:**
1. 65KB method size limit
2. Complex control flow

Note:
- Heart of the compiler
- Symbol table maps variable names to indices
- Large Perl subroutines can exceed 65KB
- Solution: fallback to interpreter mode

---

## Bytecode Example

**Perl:**
```perl
my $x = 10;
$x += 5;
```

**Generated (conceptual):**
```java
RuntimeScalar x = new RuntimeScalar(10);
x = x.add(new RuntimeScalar(5));
```

Note:
- Shows what actually gets generated
- RuntimeScalar handles Perl semantics
- JVM bytecode is similar to Java

---

# Section 3: Dual Execution Model

## Compiler vs Interpreter

Note:
- Key innovation in PerlOnJava
- Two execution pathways
- User code doesn't know which is running

---

## Why Two Execution Modes?

**The Problem:**
- JVM 65KB method limit
- Large Perl subroutines exceed this
- Dynamic `eval STRING` slow to compile

**The Solution:**
1. **Compiler Mode** (default): AST → JVM bytecode
2. **Interpreter Mode** (fallback): AST → Custom bytecode

Note:
- Both solve different problems
- Share 100% of runtime APIs
- Seamless interoperability

---

## Compiler Mode: The Fast Path

**Performance:** 1.78x faster than Perl 5

**Advantages:**
- Maximum performance after JIT warmup
- Native JVM stack frames
- JVM optimizations (inlining, escape analysis)

**Limitations:**
- 65KB method size limit
- Slower compilation for dynamic eval
- Higher memory usage

Note:
- AST → ASM → JVM bytecode → ClassLoader
- After warmup (~10K iterations), reaches peak
- Best for production, long-running code

---

## Interpreter Mode: The Flexible Path

**Performance:** 0.85x Perl 5 (15% slower)

**Advantages:**
- No method size limits
- **46x faster compilation** for eval STRING
- Lower memory footprint
- 65,536 virtual registers

**Architecture:** Register-based, not stack-based

Note:
- Custom bytecode format
- Switch-based interpreter VM
- Register-based crucial for Perl's control flow
- Stack-based would corrupt on non-local jumps

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

- **Stack-based:** Stack corrupts on non-local jumps
- **Register-based:** Explicit operands maintain correctness

Note:
- Perl's complex control flow requires registers
- Stack state becomes inconsistent with goto
- Register: rd = rs1 op rs2 (3-address code)
- ~200 opcodes covering all Perl operations

---

## Seamless Interoperability

```perl
sub large_function {
    # 10,000 lines of code
    # Runs in interpreter mode

    my $result = small_helper();  # Compiled mode!
    return $result;
}

sub small_helper {
    return 42;  # Compiled mode
}
```

**Key:** Shared runtime APIs across both modes

Note:
- Users don't need to know which mode
- Compiled code calls interpreted code seamlessly
- Same RuntimeScalar/Array/Hash/Code classes
- Closures work across boundaries

---

## eval STRING Performance

**Dynamic eval (unique strings):**

| Implementation | Time | vs Perl 5 |
|----------------|------|-----------|
| Perl 5 | 1.62s | baseline |
| **Interpreter** | **1.64s** | **1% slower** ✓ |
| Compiler | 76.12s | 47x slower ✗ |

**Interpreter is 46x faster than compiler for dynamic eval!**

Note:
- Where interpreter really shines
- Each iteration evals different string
- Compiler: expensive ASM compilation per string
- Interpreter: lightweight bytecode compilation
- Achieves Perl 5 parity

---

# Section 4: Complex Language Features

Note:
- Showcase sophisticated handling of Perl's challenges
- These are the hard problems

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

Note:
- Java uses 2 code units for emoji
- Perl counts code points, not units
- Use Character.toCodePoint(), codePointCount()
- Pack/unpack with dual representation

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

Note:
- Perl's flexible control flow is challenging
- last/next/redo with labels
- Non-local goto across scopes
- Exception handling with die/eval

---

## Control Flow: Solutions

**Technical approaches:**

1. Block-level dispatchers
2. Label tracking in symbol table
3. `die` → Java exceptions
4. `eval` → try-catch blocks
5. Proper scope boundaries

**Why register architecture helps:** Stack would corrupt

Note:
- Shared handlers for multiple exits
- Label → bytecode offset mapping
- Careful JVM frame management
- Register-based maintains state explicitly

---

## Sublanguage Parsing

**Perl embeds multiple DSLs:**

1. Regular expressions: `m/pattern/flags`
2. Pack/unpack templates: `pack("N*", @values)`
3. Sprintf format strings: `sprintf("%d", $n)`
4. Transliteration: `tr/a-z/A-Z/`
5. String interpolation: `"Value: $x\n"`

**Architecture:** Unified parser framework

Note:
- Each DSL has its own syntax
- Must parse, validate, compile at compile-time
- Enables optimization
- Consistent error messages

---

## Sublanguage Example

```perl
my $name = "World";
my $msg = "Hello $name!\n";
```

**Generated AST:**
- `LiteralNode("Hello ")`
- `VariableNode($name)`
- `LiteralNode("!")`
- `EscapeNode(\n)`

Note:
- String interpolation is complex
- Must parse literals, variables, escapes
- Compile-time validation catches errors early
- Can optimize away unnecessary operations

---

## Runtime Data Structures

**Core classes:**

1. **RuntimeScalar** - Context-aware string/number/reference
2. **RuntimeArray** - Auto-vivification, slicing, context
3. **RuntimeHash** - Lazy init, ordered keys
4. **RuntimeCode** - Code refs with closures

**Key:** Perl semantics on JVM objects

Note:
- All shared between compiler and interpreter
- Context tracking propagated through operations
- Auto-vivification implemented consistently
- Proper truthiness, string/number coercion

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

Note:
- RuntimeScalar handles Perl's dual nature
- Same variable is string and number
- RuntimeArray auto-expands
- Context determines behavior

---

# Section 5: Integration & Future

Note:
- How PerlOnJava fits in the ecosystem
- Where we're going

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

**Supports:** PostgreSQL, MySQL, Oracle, H2, SQLite, any JDBC

Note:
- Perl DBI module with JDBC drivers
- Direct database access from Perl
- No need for DBD::* drivers
- Any JDBC driver works

---

## JSR-223 Scripting API

**Call Perl from Java:**

```java
ScriptEngineManager manager = new ScriptEngineManager();
ScriptEngine perl = manager.getEngineByName("perl");

perl.put("data", myJavaObject);
Object result = perl.eval("process_data($data)");
```

**Bidirectional:** Java ↔ Perl seamlessly

Note:
- Standard Java scripting interface
- Embed Perl in Java applications
- Pass objects between languages
- Legacy Perl scripts in modern Java apps

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

Note:
- Manage dependencies with Maven
- No manual JAR management
- Integrate any Java library
- Perl's power + Java's ecosystem

---

## Current Status

**Recently Completed (v5.42.3):**
- ✅ Interpreter backend production-ready
- ✅ Full Perl 5.38+ class features
- ✅ System V IPC, socket operations
- ✅ 250,000+ tests passing

**Active Development:**
- Interpreter performance refinement
- Automatic fallback for large methods
- Optimizing eval STRING compilation

Note:
- Production-ready now
- Active development continues
- Current branch: feature/eval-interpreter-mode

---

## Roadmap: v4.0.0 (2026-05-10)

**Short-term goals:**

- Concurrency and threading support
- Interactive debugger with breakpoints
- Docker/Kubernetes configurations
- Enhanced library integration

Note:
- Focus on developer experience
- Modern deployment platforms
- Better debugging tools

---

## Roadmap: v5.0.0 (2027-04-10)

**Long-term vision:**

1. **GraalVM native image compilation**
   - Standalone native executables
   - Instant startup (no JVM warmup)
   - 10-50MB footprint vs 100-200MB

2. **Android DEX compilation**
   - Run Perl on Android devices
   - Mobile development with Perl

3. **Advanced JVM optimizations**

Note:
- GraalVM: compile to native binary
- No JVM needed to run
- Android: convert to DEX format
- Ambitious but achievable goals

---

## Ambitious Goals

- Native system integration (fork, exec, signals)
- XS module support (C extensions)
- Full CPAN compatibility
- Performance parity or better in all scenarios

Note:
- Long-term vision
- XS is challenging on JVM
- CPAN would be huge win
- Focus on compatibility and performance

---

## How to Get Involved

**Project:**
- GitHub: github.com/fglock/PerlOnJava
- License: Artistic 2.0 (same as Perl)
- 5,621+ commits since 2013

**Ways to contribute:**
1. Testing - run your scripts, report issues
2. Module porting - help with CPAN modules
3. Documentation - improve guides
4. Core development - implement features
5. Performance - identify bottlenecks

Note:
- Open source, active community
- Production-ready, sustained development
- Many ways to help

---

## Getting Started

```bash
git clone https://github.com/fglock/PerlOnJava
cd PerlOnJava
make           # Build and run tests
./jperl -E 'say "Hello from PerlOnJava!"'
```

**Resources:**
- Comprehensive documentation
- 250k+ tests as examples
- Active development

Note:
- Easy to get started
- Well-documented
- Plenty of examples to learn from

---

## Why This Matters

**PerlOnJava demonstrates:**

- Compiler engineering at scale (383 files, 250k tests)
- Creative solutions to hard problems
- Language interoperability
- Sustained engineering effort (10+ years)

**Bringing Perl's expressiveness to the JVM's platform reach**

Note:
- Significant technical achievement
- Dynamic language on modern VM
- Maintains compatibility
- Achieves competitive performance

---

## Conclusion

- ✅ **Production-ready** Perl compiler for JVM
- ✅ **1.78x faster** than Perl 5 (compiler mode)
- ✅ **250,000+ tests** passing
- ✅ **Full ecosystem integration** (JDBC, JSR-223)
- ✅ **Active development** with clear roadmap

**Proving language ecosystems can be bridged**

Note:
- Comprehensive Perl 5 compatibility
- Competitive or better performance
- Modern deployment options
- Active, sustained development

---

## Questions?

**Contact:**
- GitHub: github.com/fglock/PerlOnJava
- Issues: github.com/fglock/PerlOnJava/issues

**Thank you!**

Note:
- Open for questions
- Happy to discuss technical details
- Looking forward to contributions
