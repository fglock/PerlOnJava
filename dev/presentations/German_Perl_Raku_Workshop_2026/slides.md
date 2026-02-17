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
- Developed by Larry Wall in 1987 (39 years ago)
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

## Historical Context

**Building on 25+ years of Perl-on-JVM research:**

- **1997**: JPL (Java-Perl Library) - Brian Jepson
- **2001**: Bradley Kuhn's MS thesis on porting Perl to JVM
- **2002**: perljvm prototype using Perl's B compiler
- **2013-2023**: Perlito5 - Perl to Java/JavaScript compiler
- **2024**: PerlOnJava - Production-ready compiler and runtime

**Key insight:** Previous attempts informed this implementation

Note:
- Not the first attempt, but the first production-ready implementation
- Learned from decades of prior work
- Perlito5 was the predecessor project (same author)
- PerlOnJava represents culmination of lessons learned

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
- **Single JAR** runs on Linux, macOS, Windows
- JNA loads platform-specific features dynamically
- Debian packages (`make deb`)
- Docker containers
- Kubernetes
- Android (future)

</div>
<div>

### Performance
- Competitive with Perl 5
- JVM JIT optimization
- Higher startup time (JVM warmup)

</div>
</div>

Note:
- Enterprise: Use Perl in Java-heavy environments
- Cloud: Modern deployment platforms
- Performance: Average runtime around Perl 5 speed, with higher startup time

---

## Key Achievements

- <span class="metric">260,000+ tests passing</span>
- <span class="metric">74,000+ lines of Java code</span> (383 files)
- <span class="metric">70,000+ lines of Perl code</span> (398 bundled modules)
- <span class="metric">5,621+ commits</span>

**Bundled modules:** DBI, HTTP::Tiny, JSON, YAML, Text::CSV, and more

Note:
- Demonstrates comprehensive Perl 5 compatibility
- Scale of implementation
- Includes popular CPAN modules out of the box
- Project started June 2024 with small Perl (parser) and Java (ASM generation) prototypes
- Production-ready

---

## Recent Milestones

- Latest Perl class features

- Dual execution backend (compiler + interpreter)

- System V IPC and socket operations

- 250,000+ tests passing

Note:
- Modern OOP with class keyword
- Two execution modes for flexibility
- Production-ready networking
- Comprehensive test coverage

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

Note:
- Quick demo if doing live
- Shows it's real and working
- Conway's Life is visually impressive
- DBI example shows Java ecosystem integration

---

## Live Demo: Database Integration

**DBI with JDBC drivers:**

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

**Key advantage:** Uses JDBC drivers directly, no DBD::* adapter needed

Note:
- Perl DBI module works seamlessly with JDBC
- Any JDBC driver: PostgreSQL, MySQL, Oracle, SQLite, H2
- No need to compile C-based DBD drivers
- Pure Java integration

---

# Section 2: Compilation Pipeline

## From Perl to JVM Bytecode

Note:
- Now diving into technical details
- How we transform Perl source into executable bytecode
- Casual viewers: this is where it gets technical

---

## Background: Compilation Approaches

**Perl 5 (traditional):**
```
Perl Source → Compiler → Perl Bytecode → Bytecode Interpreter
```

**PerlOnJava (dual approach):**
```
Perl Source → Compiler → JVM Bytecode → JVM Execution
                      ↘ Custom Bytecode → Bytecode Interpreter
```

Note:
- Perl 5 compiles to internal bytecode, then interprets it
- PerlOnJava can generate either JVM bytecode or custom bytecode
- JVM bytecode: optimized by JVM JIT compiler
- Custom bytecode: more flexible, no size limits
- Both approaches use bytecode interpreters at some level

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
- Unicode code points (not UTF-16 units)
- Proper surrogate pair handling

Note:
- Breaks source into tokens: identifiers, operators, keywords
- Perl limits identifiers to 251 code points
- Must handle characters beyond U+FFFF correctly

---

## Parser: Building the AST

**Architecture:** Recursive descent parser + sub-language parsers

**Main parser:**
- Recognizes regular keywords and statements
- Handles precedence and operator resolution
- Simple recursive descent approach

**Sub-language parsers** (context-specific):
- Prototypes: `sub foo ($$@) { ... }`
- map/grep blocks: `map { ... } @list`
- Regular expressions: `m/pattern/flags`
- String interpolations: `"value: $x\n"`
- Heredocs: `<<'EOF' ... EOF`

**AST Node Types:**
- `BlockNode`, `BinaryOperatorNode`, `ListNode`
- `OperatorNode`, `MethodCallNode`

Note:
- Main parser delegates to specialized sub-parsers
- Each sub-language has its own syntax rules
- Context determines which parser to invoke
- Enables proper handling of Perl's complex syntax

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

## Compiler Mode: JVM Bytecode

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

Note:
- Standard JVM bytecode instructions
- Uses ASM library for generation
- Optimized by JVM JIT compiler at runtime

---

## Interpreter Mode: Custom Bytecode

**Perl:** `my $x = 10; say $x`

**Generated interpreter bytecode:**
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

Note:
- Custom register-based bytecode format
- Much more compact than JVM bytecode
- Explicit register operands (r3, r4, etc.)
- ~200 custom opcodes

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

**This is a common VM design pattern:**
- JVM HotSpot: bytecode interpreter + JIT compilers
- JavaScript engines (V8, SpiderMonkey): interpreter/baseline tier + optimizing JIT tier
- Ruby (CRuby): interpreter + optional JIT (MJIT/YJIT)

**The Solution:**
1. **Compiler Mode** (default): AST → JVM bytecode
2. **Interpreter Mode** (fallback): AST → Custom bytecode

Note:
- Both solve different problems
- Share 100% of runtime APIs
- Seamless interoperability

---

## Compiler Mode: The Fast Path

**Performance example (loop increment with lexical variable, 100M iterations):**
- Perl 5: 1.53s baseline
- PerlOnJava Compiler: 0.86s (1.78x faster)

**Advantages:**
- Maximum performance after JIT warmup
- Native JVM stack frames
- JVM optimizations (inlining, escape analysis)

**Limitations:**
- 65KB method size limit
- Slower compilation for dynamic eval

Note:
- AST → ASM → JVM bytecode → ClassLoader
- After warmup (~10K iterations), reaches peak
- Best for production, long-running code

---

## Interpreter Mode: The Flexible Path

**Performance example (loop increment, 100M iterations):**
- Perl 5: 1.53s baseline
- PerlOnJava Interpreter mode: 1.80s (0.85x, 15% slower)

**Advantages:**
- No method size limits
- **~19x faster** eval STRING than the compiler backend
- 65,536 virtual registers

**Architecture:** Register-based, not stack-based

Note:
- Custom bytecode format
- Switch-based interpreter VM
- Register-based crucial for Perl's control flow
- ~200 opcodes covering all Perl operations
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

- **Stack-based:** Easy to corrupt the stack on non-local jumps
- **Register-based:** Explicit operands maintain correctness

Note:
- Perl's complex control flow requires registers
- Stack state becomes inconsistent with goto
- Register: rd = rs1 op rs2 (3-address code)
- ~200 opcodes covering all Perl operations

---

## JVM Ecosystem: Current and Future

**Current support: Standard JVM (HotSpot)**
- Oracle JDK, OpenJDK
- Compiler mode generates standard JVM bytecode
- Runs on any compliant JVM

**Future targets (v5.0.0 - 2027):**

1. **GraalVM** - Native image compilation
   - Compile to standalone native executables
   - Instant startup, no JVM warmup
   - Smaller footprint

2. **Android DEX** - Mobile platform
   - Convert JVM bytecode → Dalvik bytecode
   - Run Perl on Android devices

**Key advantage: Interpreter backend enables portability**
- Custom bytecode format is platform-independent
- Can be ported to any JVM derivative
- Compiler mode ties us to standard JVM bytecode

Note:
- Interpreter mode is the key to multi-platform support
- GraalVM and DEX support planned for future releases
- Interpreter's custom bytecode can run anywhere
- This is why dual backend matters beyond just performance

---

## eval STRING Performance

**Dynamic eval (1,000,000 unique strings):**

| Implementation | Time | vs Perl 5 |
|----------------|------|-----------|
| Perl 5 | 1.25s | baseline |
| PerlOnJava | 6.00s | 4.80x slower |

**Note:** PerlOnJava automatically uses interpreter mode for eval STRING

Note:
- Interpreter mode used automatically for dynamic eval
- Each iteration evals a different string
- Compilation overhead dominates runtime
- Still functional for typical eval usage patterns

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

- We implement these using **tagged return values** (`RuntimeControlFlowList`) that carry control-flow markers across subroutine boundaries

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
6. **Tagged return values** for control flow
    - Return `RuntimeControlFlowList` (tagged list) instead of regular `RuntimeList`
    - Tag contains control flow type (last/next/redo/goto/tail-call) and target label
    - Caller checks tag and dispatches accordingly
    - If list is not tagged, program continues normally
    - Implements Perl tail calls (`goto &sub`) and non-local jumps safely

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

**Architecture:** Each DSL has its own dedicated parser

- Parses DSL syntax into AST nodes
- Validates at compile-time (catches errors early)
- Generates optimized bytecode
- Provides consistent error messages

Note:
- String interpolation parser: breaks `"$x\n"` into literals, variables, escapes
- Regex parser: parses pattern syntax; translation to Java regex happens at regex compile time
- Pack/unpack parser: validates templates, generates format handlers
- Each parser is invoked by main parser when context requires it

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
- Must parse literals, variables escapes
- Compile-time validation catches errors early
- Can optimize away unnecessary operations

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

**Result:** Most Perl regex features work seamlessly

Note:
- Regex cache (1000 patterns) for performance
- Some advanced features not supported (recursive patterns, variable-length lookbehind)
- See feature-matrix.md for complete regex support details

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

**Key difference:** XS → Java Native Access (JNA) for native libraries

Note:
- Perl 5 XS modules won't work directly on PerlOnJava
- But we can create Java equivalents with same API
- Advantage: easier to write and maintain than C/XS
- Access to entire Java ecosystem instead of C libraries
- Some CPAN modules being ported to Java backend

---

## Current Limitations

**JVM-incompatible features:**
-  `fork` - not available on JVM
-  `DESTROY` blocks - Perl uses reference counting for deterministic cleanup; JVM uses non-deterministic garbage collection
-  Threading - not yet implemented
-  Perl XS modules - C extensions don't work (use Java equivalents)

**Partially implemented:**
-  Some regex features (recursive patterns, variable-length lookbehind)
-  Some warnings and pragmas
-  Taint checks

**Workarounds available:**
- Use JNA for native library access instead of XS
- Use Java threading APIs instead of Perl threads
- File auto-close happens at program end

Note:
- Most limitations are JVM-related, not design choices
- Many CPAN modules being ported to Java backend
- XSLoader works with Java implementations
- See feature-matrix.md for complete details

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
-  Interpreter backend production-ready
-  Full Perl class features
-  System V IPC, socket operations

**Active Development:**
- Interpreter performance refinement
- Automatic fallback for large methods
- Optimizing eval STRING compilation
- 260,000+ tests passing

Note:
- Production-ready now
- Active development continues

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
- ✅ **Competitive performance** with Perl 5 (1.78x faster in compiler mode, 0.85x in interpreter mode)
- ✅ **260,000+ tests** passing
- ✅ **Full ecosystem integration** (JDBC, JSR-223)
- ✅ **Active development** with clear roadmap

**Proving language ecosystems can be bridged**

Note:
- Comprehensive Perl 5 compatibility
- Compiler mode: 1.78x faster on loop benchmarks
- Interpreter mode: 19x faster eval compilation
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
