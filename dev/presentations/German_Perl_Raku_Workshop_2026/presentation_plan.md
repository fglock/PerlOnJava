# PerlOnJava: 40-Minute Presentation Plan

## Context

This presentation plan is for a recorded 40-minute talk about the PerlOnJava project, targeting a mixed audience. The structure is designed so that casual viewers gain value from the first 5-8 minutes (project overview and key achievements), while technically-interested viewers will continue for deeper technical insights into the implementation challenges and solutions.

The presentation follows a "wide to narrow" approach: starting with general context about what PerlOnJava is and what it achieves, then progressively diving into technical details about how it works.

**Note**: Some specific numbers in this plan may need verification against current project status. They serve as placeholders for the actual metrics at presentation time.

---

## Presentation Structure Overview

**Total Time**: 40 minutes

1. **Introduction & Key Achievements** (8 minutes) - *Early wins for casual viewers*
2. **Compilation Pipeline: From Perl to JVM Bytecode** (8 minutes) - *Technical deep dive #1*
3. **Dual Execution Model: Compiler vs Interpreter** (8 minutes) - *Technical deep dive #2*
4. **Complex Language Features** (8 minutes) - *Technical deep dive #3*
5. **Integration, Future Directions & Wrap-up** (8 minutes) - *Ecosystem and roadmap*

---

## Section 1: Introduction & Key Achievements (8 minutes)

**Goal**: Hook the audience quickly with clear value proposition and impressive results. Casual viewers should leave satisfied after this section.

### 1.0 What is Perl? (1 minute)

**For People Completely Outside Perl**:

From www.perl.org:
- Perl is a highly capable, feature-rich programming language with over 35 years of development
- Perl runs on over 100 platforms from portables to mainframes and is suitable for both rapid prototyping and large scale development projects
- Known for text processing, system administration, web development, network programming, and more
- Created by Larry Wall in 1987
- Powers major websites and systems worldwide
- "Perl makes easy things easy and hard things possible"

**Key Points**:
- Mature, battle-tested language with decades of real-world use
- Powerful text processing and pattern matching capabilities
- Comprehensive module ecosystem (CPAN - Comprehensive Perl Archive Network)
- Still widely used in enterprise, bioinformatics, finance, DevOps

### 1.1 What is PerlOnJava? (2 minutes)

**Talking Points**:
- PerlOnJava is a Perl compiler and runtime for the Java Virtual Machine
- It compiles Perl scripts into native JVM bytecode (not just wrapping an interpreter)
- Enables Perl to run in Java environments: servers, Android devices, cloud platforms
- Maintains Perl semantics while gaining JVM benefits: cross-platform consistency, integration with Java libraries, access to JVM tooling

**Key Statement**: "Perl, running natively on the JVM - not an interpreter wrapped in Java, but true compilation to Java bytecode."

**Visual Suggestion**: Simple diagram showing: `Perl Script → PerlOnJava Compiler → JVM Bytecode → Runs Anywhere`

**Live Terminal Demo Suggestions** (pick 1-2):
```bash
# Simple hello world
./jperl -E 'say "Hello from PerlOnJava!"'

# Conway's Game of Life (visual, impressive)
./jperl examples/life.pl

# JSON processing
./jperl examples/json.pl

# Just Another Perl Hacker (classic Perl demo)
./jperl examples/japh.pl
```

### 1.2 Why Does This Matter? (1 minute)

**Three Key Value Propositions**:

1. **Enterprise Integration**: Use Perl scripts in Java-heavy enterprise environments
   - Direct JDBC database access (PostgreSQL, MySQL, Oracle, H2)
   - Maven dependency management for external libraries
   - JSR-223 Scripting API (call Perl from Java seamlessly)

2. **Modern Deployment**: Deploy Perl to modern cloud and mobile platforms
   - Docker containers with consistent JVM runtime
   - Kubernetes orchestration
   - Future: Android apps, GraalVM native images

3. **Performance**: Competitive or better than native Perl
   - 78% faster than Perl 5 on numeric benchmarks (compiler mode)
   - JVM JIT optimization benefits after warmup

**Visual Suggestion**: Three columns showing use cases: Enterprise (database icon), Cloud (container icon), Performance (speedometer icon)

### 1.3 Key Achievements & Current Status (4 minutes)

**Impressive Metrics** (use these to establish credibility):

- **250,000+ tests passing** - demonstrates comprehensive Perl 5 compatibility
- **150+ core Perl modules** bundled (JSON, YAML, HTTP::Tiny, DBI, etc.)
- **383 Java source files** implementing the compiler and runtime
- **341 Perl standard library modules** included
- **5,621+ commits** showing active, sustained development

**Recent Milestones**:
- ✅ Full Perl 5.38+ class features (modern OOP syntax)
- ✅ Dual execution backend (compiler + interpreter modes)
- ✅ System V IPC and socket operations
- ✅ Production-ready with comprehensive test coverage

**Performance Numbers** (show concrete benchmark):
```
Loop Increment Test (100 million iterations):
- Perl 5:                1.53s (baseline)
- PerlOnJava Compiler:   0.86s (1.78x faster)
- PerlOnJava Interpreter: 1.80s (0.85x, 15% slower)
```

**Real-World Examples**:
- Conway's Game of Life (complex data structures, terminal output)
- Database operations (DBI with JDBC drivers)
- HTTP APIs (HTTP::Tiny, JSON processing)
- All running on JVM bytecode

**Transition Statement**: "So that's what PerlOnJava is and what it achieves. For the rest of this talk, I'll dive into the technical details of how we built this - the challenges we faced and the solutions we developed."

---

## Section 2: Compilation Pipeline - From Perl to JVM Bytecode (8 minutes)

**Goal**: Explain the technical architecture of transforming Perl source code into executable JVM bytecode.

### 2.0 Background: Compilers, Interpreters, and the JVM (1.5 minutes)

**For Mixed Technical Audience - Quick Primer**:

**What's a Compiler?**
- Translates source code (human-readable) into machine code (CPU-executable)
- Examples: C compiler (gcc), Java compiler (javac)
- Translation happens once, then the code runs fast
- Catches errors at compile-time

**What's an Interpreter?**
- Reads and executes source code directly, line by line
- Examples: Python, Ruby, original Perl
- No separate compilation step - just run the code
- More flexible but typically slower

**What's the JVM (Java Virtual Machine)?**
- A "virtual computer" that runs bytecode (JVM instructions)
- Java code compiles to bytecode, JVM runs it
- "Write once, run anywhere" - same bytecode works on Windows, Mac, Linux, Android
- Has a sophisticated JIT (Just-In-Time) compiler that optimizes code while it runs
- Runs many languages: Java, Kotlin, Scala, Groovy, and now... Perl!

**PerlOnJava's Approach**:
- Compiles Perl → JVM bytecode (like javac does for Java)
- JVM runs the bytecode with its powerful optimizations
- Also has an interpreter mode as fallback (we'll cover why later)

**Visual Suggestion**: Side-by-side comparison:
```
Traditional:  Perl Source → Perl Interpreter → Execution
PerlOnJava:   Perl Source → Compiler → JVM Bytecode → JVM Execution
```

### 2.1 Pipeline Overview (1.5 minutes)

**The Five-Stage Pipeline**:
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

**Key Insight**: This is a true compiler, not an interpreter. Perl source becomes JVM bytecode indistinguishable from Java-compiled bytecode.

### 2.2 Lexer: Tokenization Challenges (1.5 minutes)

**What It Does**:
- Breaks Perl source into tokens: identifiers, operators, keywords, literals
- Handles Perl's complex syntax (context-sensitive operators, sigils)

**Technical Challenge**: Unicode identifier support
- Perl limits identifiers to 251 Unicode code points (not UTF-16 code units)
- Must handle surrogate pairs correctly (characters beyond U+FFFF)
- Solution: IBM's ICU4J library for proper Unicode handling

**Example**:
```perl
my $café_123 = 42;  # Valid Perl identifier
my $𝕦𝕟𝕚𝕔𝕠𝕕𝕖 = "test";  # Mathematical bold symbols (surrogate pairs)
```

**Performance**: ~50,000 lines of Perl per second

### 2.3 Parser: Building the AST (2 minutes)

**What It Does**:
- Converts token stream into Abstract Syntax Tree (AST)
- Handles Perl's complex precedence rules
- Resolves ambiguities (operators vs. function calls)

**Technical Challenge**: Context-aware parsing
- Same syntax can mean different things based on context
- Example: `<=>` can be spaceship operator OR empty qw() delimiter
- Solution: Parser implements lookahead and context tracking

**AST Node Types** (examples from `org.perlonjava.astnode`):
- `BlockNode` - code blocks
- `BinaryOperatorNode` - operations like `$a + $b`
- `ListNode` - arrays and lists
- `OperatorNode` - general operators
- `MethodCallNode` - object method calls

### 2.4 EmitterVisitor: Generating Bytecode (2 minutes)

**What It Does**:
- Traverses AST and generates JVM bytecode using ASM library
- Manages symbol tables (variable name → local variable index)
- Handles context propagation (void/scalar/list context)

**Technical Challenges**:
1. **JVM Method Size Limit**: 65KB bytecode per method
   - Large Perl subroutines can exceed this
   - Solution: Automatic chunking or fallback to interpreter mode

2. **Control Flow Complexity**:
   - Perl's `goto LABEL`, `last`/`next`/`redo` across nested blocks
   - Exception handling with `die`/`eval`
   - Solution: Block-level dispatchers with proper scope tracking

**Code Example** (what gets generated):
```perl
my $x = 10;
$x += 5;
```
Becomes JVM bytecode similar to:
```java
RuntimeScalar x = new RuntimeScalar(10);
x = x.add(new RuntimeScalar(5));
```

**Key Files**:
- `src/main/java/org/perlonjava/astvisitor/EmitterVisitor.java`
- `src/main/java/org/perlonjava/codegen/EmitterMethodCreator.java`

---

## Section 3: Dual Execution Model - Compiler vs Interpreter (8 minutes)

**Goal**: Explain the architecture decision to support two execution backends and the trade-offs involved.

### 3.1 Why Two Execution Modes? (2 minutes)

**The Problem**:
- JVM has hard 65KB limit on method bytecode size
- Large Perl subroutines can generate more bytecode than this
- Dynamic `eval STRING` requires runtime compilation, which is slow with ASM

**The Solution**: Two execution pathways
1. **Compiler Mode** (default): AST → JVM bytecode via ASM library
2. **Interpreter Mode** (fallback): AST → Custom bytecode → Switch-based VM

**Key Innovation**: Both modes share 100% of runtime APIs
- Same `RuntimeScalar`, `RuntimeArray`, `RuntimeHash` classes
- Compiled code can call interpreted code (and vice versa)
- Closures work across mode boundaries
- User code doesn't know or care which mode is running

### 3.2 Compiler Mode: The Fast Path (2 minutes)

**How It Works**:
- AST → ASM library → JVM bytecode → Custom ClassLoader
- JVM JIT compiler optimizes bytecode at runtime
- After warmup (typically 10,000+ iterations), reaches peak performance

**Performance**: ~82M operations/second (1.78x faster than Perl 5)

**Advantages**:
- Maximum performance after JIT warmup
- Native JVM stack frames (good for debugging)
- Benefits from JVM's sophisticated optimizations

**Limitations**:
- 65KB method size limit
- Slower compilation time for dynamic eval
- Higher memory usage for generated classes

### 3.3 Interpreter Mode: The Flexible Path (2 minutes)

**How It Works**:
- AST → Custom bytecode format → Switch-based interpreter
- Register-based architecture (not stack-based)
- 65,536 virtual registers per method (vs 256 JVM local variables)

**Performance**: ~47M operations/second (0.85x vs Perl 5, 1.75x slower than compiler mode)

**Advantages**:
- No method size limits
- 46x faster compilation for eval STRING
- Lower memory footprint
- Simpler bytecode (easier to debug/introspect)

**Critical Design Decision: Why Register-Based?**

Perl's complex control flow would corrupt a stack-based interpreter:
```perl
eval {
    for my $i (1..10) {
        goto LABEL if $i > 5;
    }
};
LABEL: print "Jumped out!\n";
```

Stack-based: Stack state becomes inconsistent after non-local jumps
Register-based: Explicit register operands maintain correctness

**Bytecode Format**:
- 3-address code: `rd = rs1 op rs2`
- Short[] array encoding: `[opcode, rd, rs1, rs2, ...]`
- ~200 opcodes covering all Perl operations

### 3.4 Seamless Interoperability (2 minutes)

**The Magic**: Users don't need to know which mode is running

**Example Scenario**:
```perl
sub large_function {
    # 10,000 lines of code
    # Too large for JVM method limit
    # Automatically runs in interpreter mode

    my $result = small_helper();  # Compiled mode
    return $result;
}

sub small_helper {
    # Small function, runs in compiler mode
    return 42;
}
```

**Technical Implementation**:
- Shared `RuntimeScalar`, `RuntimeArray`, `RuntimeHash` implementations
- Shared global variable maps (`$::var`, `@::array`, `%::hash`)
- Shared symbol table for variable resolution
- Closure capture works across boundaries

**Use Case: Dynamic eval**:
```perl
my $code = get_user_input();  # Dynamic string
eval $code;  # Uses interpreter mode (46x faster compilation)
```

**Key Files**:
- `src/main/java/org/perlonjava/codegen/BytecodeCompiler.java` - Interpreter bytecode generator
- `src/main/java/org/perlonjava/codegen/BytecodeInterpreter.java` - Interpreter execution engine
- `docs/design/interpreter_backend.md` - 60KB architecture document

---

## Section 4: Complex Language Features (8 minutes)

**Goal**: Showcase the sophisticated handling of Perl's most challenging features.

### 4.1 Unicode and String Handling (2 minutes)

**The Challenge**: Java's UTF-16 vs Perl's flexible encoding

**Java's Model**:
- Strings are UTF-16 internally
- Characters beyond U+FFFF use surrogate pairs (2 code units)
- Example: 𝕦 (U+1D566) = `\uD835\uDD66` (high + low surrogate)

**Perl's Model**:
- Strings can be bytes or Unicode code points
- Dual representation: character codes + byte data
- Length counted in code points, not code units

**Solution**:
- Use `Character.toCodePoint()` for full Unicode code points
- Use `String.codePointCount()` for proper length
- Implement pack/unpack with dual representation (character codes + byte data)

**Example**:
```perl
my $emoji = "👍";  # U+1F44D
say length($emoji);  # Must return 1, not 2
say ord($emoji);     # Must return 128077 (0x1F44D)
```

**Key Implementation**:
- `src/main/java/org/perlonjava/operators/Unpack.java` - Binary data with Unicode state tracking
- `parser/IdentifierParser.java` - 251 code point identifier limit

### 4.2 Control Flow and Non-Local Jumps (2 minutes)

**The Challenge**: Perl's flexible control flow

**Features to Support**:
```perl
# Labeled loops with last/next/redo
OUTER: for my $i (1..10) {
    INNER: for my $j (1..10) {
        last OUTER if $i * $j > 50;  # Jump out two levels
        next INNER if $j == 5;       # Skip iteration
        redo INNER if some_condition();  # Restart iteration
    }
}

# Non-local goto
sub foo {
    goto ELSEWHERE if $error;
    # ...
}
ELSEWHERE: print "Jumped here from function!\n";

# Exception handling
eval {
    die "Error!" if $problem;
};
print "Caught: $@\n" if $@;
```

**Technical Solutions**:

1. **Block-Level Dispatchers**: Shared handlers for multiple control flow exits
2. **Label Tracking**: Symbol table maintains label → bytecode offset mapping
3. **Exception Integration**: `die` maps to Java exceptions, `eval` to try-catch
4. **Scope Boundaries**: Careful handling to prevent JVM frame computation failures

**Why Register Architecture Helps**:
- Stack-based would corrupt on non-local jumps
- Register-based maintains state explicitly

**Key Files**:
- `dev/design/CONTROL_FLOW_IMPLEMENTATION.md`
- `src/main/java/org/perlonjava/astvisitor/EmitterVisitor.java` (visitLabel, visitGoto methods)

### 4.3 Sublanguage Parsing (2 minutes)

**The Challenge**: Perl embeds multiple domain-specific languages

**Embedded DSLs in Perl**:
1. **Regular Expressions**: `m/pattern/flags`
2. **Pack/Unpack Templates**: `pack("N*", @values)`
3. **Sprintf Format Strings**: `sprintf("%d %s", $n, $str)`
4. **Transliteration**: `tr/a-z/A-Z/`
5. **String Interpolation**: `"Value: $x\n"`

**Architecture Solution**: Unified sublanguage parser framework

**Pattern**:
```
Input String → Sublanguage Lexer → Sublanguage Parser → AST → Validation → Bytecode
```

**Example: String Interpolation**:
```perl
my $name = "World";
my $msg = "Hello $name!\n";  # Must parse: literal + variable + literal + escape
```

Generates AST:
- LiteralNode("Hello ")
- VariableNode($name)
- LiteralNode("!")
- EscapeNode(\n)

**Example: Pack/Unpack Templates**:
```perl
my $binary = pack("N3", 1, 2, 3);  # Three big-endian 32-bit integers
```

Generates:
- FormatHandler("N", count=3, values=[1,2,3])
- With proper endianness and byte ordering

**Benefits**:
- Compile-time validation (catch errors early)
- Optimization opportunities
- Consistent error messages
- Two-tier errors: SYNTAX_ERROR (user mistake) vs UNIMPLEMENTED_ERROR (not yet supported)

**Key Files**:
- `docs/design/sublanguage_parser_architecture.md` - 1,230-line architecture document
- `src/main/java/org/perlonjava/parser/StringDoubleQuoted.java`
- `docs/design/pack_unpack_architecture.md`

### 4.4 Runtime Data Structures (2 minutes)

**The Challenge**: Perl semantics on JVM objects

**Core Runtime Classes**:

1. **RuntimeScalar**: Context-aware string/number/reference handling
   ```perl
   my $x = "10";
   my $y = $x + 5;     # String becomes number: 15
   my $z = $x . " cats"; # Number becomes string: "10 cats"
   ```

2. **RuntimeArray**: Auto-vivification, slicing, context sensitivity
   ```perl
   my @arr;
   $arr[10] = "x";     # Auto-expands array to 11 elements
   my @slice = @arr[2..5];  # Array slice
   my $count = @arr;   # Scalar context: returns length
   ```

3. **RuntimeHash**: Lazy initialization, ordered keys
   ```perl
   my %hash;
   $hash{a}{b}{c} = 1;  # Auto-vivifies nested hashes
   my @keys = keys %hash;  # Maintains insertion order
   ```

4. **RuntimeCode**: Code references with closure capture
   ```perl
   sub make_counter {
       my $count = 0;
       return sub { ++$count };  # Captures $count
   }
   ```

**Key Implementation Details**:
- All runtime classes share same API between compiler and interpreter modes
- Context tracking (void/scalar/list) propagated through all operations
- Auto-vivification implemented consistently
- Proper Perl semantics for truthiness, string/number coercion

**Key Files**:
- `src/main/java/org/perlonjava/runtime/RuntimeScalar.java`
- `src/main/java/org/perlonjava/runtime/RuntimeArray.java`
- `src/main/java/org/perlonjava/runtime/RuntimeHash.java`
- `src/main/java/org/perlonjava/runtime/RuntimeCode.java`

---

## Section 5: Integration, Future Directions & Wrap-up (8 minutes)

**Goal**: Show ecosystem integration, roadmap, and how to get involved.

### 5.1 Java Ecosystem Integration (3 minutes)

**Three Integration Points**:

**1. JDBC Database Access (DBI)**
```perl
use DBI;
my $dbh = DBI->connect("jdbc:postgresql://localhost/mydb",
                       $user, $password);
my $sth = $dbh->prepare("SELECT * FROM users WHERE id = ?");
$sth->execute($user_id);
while (my $row = $sth->fetchrow_hashref) {
    say "User: $row->{name}";
}
```

**Supported Databases**: PostgreSQL, MySQL, Oracle, H2, SQLite, any JDBC driver

**2. JSR-223 Scripting API**
```java
// Call Perl from Java
ScriptEngineManager manager = new ScriptEngineManager();
ScriptEngine perl = manager.getEngineByName("perl");

perl.put("data", myJavaObject);
Object result = perl.eval("process_data($data)");
```

**Bidirectional**: Java calls Perl, Perl calls Java seamlessly

**3. Maven Dependency Management**
```bash
./Configure.pl --search mysql-connector-java
# Automatically downloads JDBC drivers
# Adds to classpath for use in Perl scripts
```

**Real Use Cases**:
- Legacy Perl scripts in modern Java applications
- ETL pipelines with Perl text processing + JDBC databases
- Embedded scripting in Java applications
- Cross-language integration projects

### 5.2 Current Development & Roadmap (3 minutes)

**Recently Completed (v5.42.3)**:
- ✅ Interpreter backend production-ready
- ✅ Full Perl 5.38+ class features
- ✅ System V IPC operators (msgctl, semctl, shmctl)
- ✅ Socket operations (socket, bind, listen, accept)
- ✅ 250,000+ tests passing

**Active Development (Current Branch: feature/eval-interpreter-mode)**:
- Refining interpreter performance
- Automatic fallback for large methods
- Optimizing eval STRING compilation

**Short-Term Roadmap (v4.0.0 - Target: 2026-05-10)**:
- Concurrency and threading support
- Interactive debugger with breakpoints
- Docker/Kubernetes deployment configurations
- Enhanced external library integration

**Long-Term Vision (v5.0.0 - Target: 2027-04-10)**:
- **GraalVM native image compilation**: Compile Perl scripts to standalone native executables
  - Instant startup (no JVM warmup)
  - Minimal memory footprint (~10-50MB vs 100-200MB for JVM)
  - Single binary deployment
  - Future: Polyglot integration (JavaScript, Python, Ruby on same runtime)
- **Android DEX compilation**: Convert JVM bytecode to Android's Dalvik bytecode (DEX format)
  - Run Perl apps natively on Android devices
  - Android app with native UI components
  - Mobile development with Perl
- Advanced JVM optimizations

**Ambitious Goals**:
- Native system integration (fork, exec, signals)
- XS module support (Perl extensions in C)
- Full CPAN compatibility
- Performance parity or better than Perl 5 in all scenarios

### 5.3 How to Get Involved & Conclusion (2 minutes)

**Project Information**:
- **GitHub**: github.com/fglock/PerlOnJava
- **License**: Artistic License 2.0 (same as Perl)
- **Status**: Production-ready, actively developed
- **5,621+ commits**, sustained development since 2013

**Ways to Contribute**:
1. **Testing**: Run your Perl scripts, report issues
2. **Module Porting**: Help port CPAN modules
3. **Documentation**: Improve guides and examples
4. **Core Development**: Implement missing features
5. **Performance**: Identify and optimize bottlenecks

**Getting Started**:
```bash
git clone https://github.com/fglock/PerlOnJava
cd PerlOnJava
make           # Build and run tests
./jperl -E 'say "Hello from PerlOnJava!"'
```

**Why This Matters**:

PerlOnJava represents a significant technical achievement: bringing a dynamic, 30-year-old language with complex semantics to a modern virtual machine while maintaining compatibility and achieving competitive performance.

It demonstrates:
- Compiler engineering at scale (383 Java files, 250k+ tests)
- Creative solutions to hard problems (dual execution, register-based VM)
- Language interoperability (seamless Perl-Java integration)
- Sustained engineering effort (5,600+ commits over 10+ years)

**Final Statement**: "PerlOnJava proves that with careful design and sustained effort, we can bridge language ecosystems and bring the best of both worlds - Perl's expressiveness and the JVM's platform reach - together."

---

## Key Metrics for Reference (Throughout Presentation)

Use these numbers strategically throughout the talk:

- **250,000+ tests passing** (comprehensiveness)
- **383 Java source files** (scale of implementation)
- **150+ core modules bundled** (functionality)
- **1.78x faster than Perl 5** (performance - compiler mode)
- **46x faster eval compilation** (interpreter mode advantage)
- **5,621+ commits** (sustained development)
- **65,536 virtual registers** (interpreter capacity)
- **50,000 lines/sec** (lexer speed)
- **341 Perl stdlib modules** (ecosystem)

---

## Critical Files Reference

For deeper exploration or questions:

**Architecture Documentation**:
- `docs/ARCHITECTURE.md` - Overall system design
- `docs/design/interpreter_backend.md` - Dual execution model (60KB)
- `docs/design/sublanguage_parser_architecture.md` - DSL parsing framework
- `docs/FEATURE_MATRIX.md` - 700+ item compatibility matrix
- `MILESTONES.md` - Progress and roadmap

**Core Implementation**:
- `src/main/java/org/perlonjava/lexer/Lexer.java` - Tokenization
- `src/main/java/org/perlonjava/parser/Parser.java` - AST construction
- `src/main/java/org/perlonjava/astvisitor/EmitterVisitor.java` - Bytecode emission
- `src/main/java/org/perlonjava/codegen/BytecodeCompiler.java` - Interpreter bytecode
- `src/main/java/org/perlonjava/codegen/BytecodeInterpreter.java` - Interpreter VM
- `src/main/java/org/perlonjava/runtime/RuntimeScalar.java` - Core data structure

**Testing**:
- `src/test/resources/unit/` - 156 fast unit tests
- `dev/tools/perl_test_runner.pl` - Test harness

---

## Presentation Tips

1. **First 5 Minutes Are Critical**: Hook the audience with clear value and impressive results
2. **Use Concrete Examples**: Show actual Perl code and what happens to it
3. **Visual Aids**: Diagrams for pipeline, architecture, performance comparisons
4. **Live Demo (Optional)**: Quick `./jperl` demo if doing this live
5. **Emphasize Scale**: 250k tests, 383 files, 5600+ commits - this is serious engineering
6. **Balance Breadth and Depth**: Cover many topics, but dive deep on 2-3 key innovations
7. **End with Call to Action**: Invite contributions, provide clear next steps

---

## Time Management Checkpoints

- **5 minutes**: Should have finished Section 1 (Introduction & Achievements)
- **13 minutes**: Should have finished Section 2 (Compilation Pipeline)
- **21 minutes**: Should have finished Section 3 (Dual Execution Model)
- **29 minutes**: Should have finished Section 4 (Complex Features)
- **37 minutes**: Should be wrapping up Section 5 (Integration & Future)
- **40 minutes**: Done!

Leave 2-3 minutes buffer for transitions and Q&A setup.
