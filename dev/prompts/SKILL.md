# PerlOnJava Debugging Skills and Architecture Knowledge

This document captures key knowledge about PerlOnJava internals learned during debugging sessions.

## Variable Storage and Scoping

### Three Types of Variable Declarations

1. **`my` variables** - Lexical scope
   - Stored in JVM local variable slots during normal execution
   - When captured by closures: stored as closure fields or in GlobalVariable with IDs
   - Symbol table entry: `decl = "my"`, has `index` (JVM slot number)

2. **`our` variables** - Package scope with lexical declaration
   - Actually stored in GlobalVariable (package namespace)
   - Symbol table entry: `decl = "our"`, has `index` but uses package name
   - Access: `GlobalVariable.getGlobalVariable("Package::varname")`

3. **`use vars` variables** - Package scope without lexical declaration
   - **No symbol table entry at all**
   - Directly stored in GlobalVariable
   - Imported at runtime by `Vars.importVars()`
   - Access: `GlobalVariable.getGlobalVariable("Package::varname")`

### File-Scope Variables and IDs

When large code blocks are refactored, file-scope `my` variables can get **IDs** assigned:

```perl
my %allGroups = ();  # File-scope lexical

# Large block triggers refactoring
for (1..10000) { ... }  # This creates closures

# Now %allGroups has an ID (e.g., 75)
```

**What happens:**
- `SubroutineParser.handleNamedSubWithFilter()` line 700-709 assigns IDs to variables that need to be captured
- The variable is moved to GlobalVariable with key `PerlOnJava::_BEGIN_75::allGroups`
- References to it call `PersistentVariable.retrieveBeginHash("allGroups", 75)`
- This retrieves from GlobalVariable, not JVM slots

**Key insight:** Variables with IDs behave like BEGIN variables even if not in BEGIN blocks.

### GlobalVariable Storage

The GlobalVariable registry is a runtime storage system:

```java
// Storage maps
private static final ConcurrentHashMap<String, RuntimeScalar> globalVariables
private static final ConcurrentHashMap<String, RuntimeArray> globalArrays
private static final ConcurrentHashMap<String, RuntimeHash> globalHashes

// Access
RuntimeHash hash = GlobalVariable.getGlobalHash("Package::varname");
// Creates new empty hash if doesn't exist
```

**Important methods:**
- `getGlobalHash()` - Returns existing or creates new
- `removeGlobalHash()` - Removes from map and returns (transfer ownership)
- `existsGlobalHash()` - Check if exists (for strict vars)

## BEGIN Blocks

### Compile-Time Execution

BEGIN blocks execute **during parsing**, not at runtime:

```perl
my $x;
BEGIN { $x = 42 }  # Executes immediately when parsed
print $x;          # Uses the value set at compile-time
```

**Implementation:**
1. Parser encounters BEGIN block → executes it immediately
2. Variables modified in BEGIN are stored in GlobalVariable with unique IDs
3. The BEGIN block itself is removed from the AST (doesn't appear in generated code)
4. Later references retrieve from GlobalVariable using `PersistentVariable.retrieveBegin*()`

**Storage pattern:**
```
Variable: $x in BEGIN block with ID 42
Storage key: PerlOnJava::_BEGIN_42::x
Retrieved via: PersistentVariable.retrieveBeginScalar("$x", 42)
```

### Why BEGIN Variables Work Across Refactoring

BEGIN variables use **name-based lookup**, not JVM slot numbers:
- Normal `my` variables: `ALOAD <slot>` (breaks if slot reallocated)
- BEGIN variables: `PersistentVariable.retrieveBeginScalar(name, id)` (always works)

## Closures and Variable Capture

### How Closures Are Created

When a subroutine/closure is created, it captures variables from outer scopes:

```perl
my $outer = 1;
my $closure = sub { $outer + 1 };  # Captures $outer
```

**Implementation (`SubroutineParser.java` lines 680-730):**

1. **Scan outer symbol tables** for variables the closure references
2. **Determine storage:**
   - If `decl == "our"`: Use package name, store as GlobalVariable reference
   - If `decl == "my"` or `"state"`:
     - If has ID: Use `PersistentVariable.retrieveBegin*()`
     - If no ID: Use JVM slot (captured as constructor parameter)
3. **Create closure class** with fields for captured variables
4. **Constructor** receives captured variables and stores in fields
5. **apply()** method accesses variables via fields

### Capture By Reference

**Critical:** Closures capture variables **by reference**, not by value:

```perl
my $x = 1;
my $c1 = sub { $x };
$x = 2;
my $c2 = sub { $x };
print $c1->();  # Prints 2 (not 1!)
print $c2->();  # Prints 2
```

Both closures see the same `$x` reference. Changes are visible to all closures.

### Closure Instantiation vs Execution

**Two phases:**

1. **Instantiation** (closure constructor):
   ```
   NEW org/perlonjava/anon42
   DUP
   ALOAD <captured_var_1>  // Variables captured here
   ALOAD <captured_var_2>
   INVOKESPECIAL org/perlonjava/anon42.<init>
   ```
   Variables are captured during instantiation, even if uninitialized.

2. **Execution** (closure apply):
   ```
   ALOAD <closure_object>
   ALOAD <args>
   ILOAD <context>
   INVOKEVIRTUAL org/perlonjava/anon42.apply
   ```
   Variables are accessed/used during execution.

**Key insight:** If a variable is initialized AFTER closure instantiation but BEFORE execution, the closure sees the initialized value (because it captured by reference).

## Method Too Large and Refactoring

### The JVM Limit

The JVM has a hard limit: **65,535 bytes per method**. Large Perl code blocks can exceed this.

### Refactoring Strategy

`LargeBlockRefactorer.trySmartChunking()` splits large blocks:

```
Original: 10,000 statements in one method
↓
Refactored: sub { 4000 statements, sub { 3000 statements, sub { 3000 statements }->() }->() }->()
```

### The Algorithm

**Two paths:**

1. **`treatAllElementsAsSafe = true`** (no labels, no control flow):
   - Skip backward iteration
   - Process all elements as one safe run
   - Create chunks from the end

2. **`treatAllElementsAsSafe = false`** (has labels or control flow):
   - Iterate backward from end
   - Identify "chunk breakers" (labels, return, die, etc.)
   - Create safe runs between breakers

**Chunking process:**
```
Start with all elements as one safe run:
  safeRunLen = 100
  safeRunEndExclusive = 100

Iteration 1: Take chunk [60..99]
  chunkStart = 60
  Create closure with elements [60..99]
  Update: safeRunEndExclusive = 60, safeRunLen = 60

Iteration 2: Take chunk [30..59]
  chunkStart = 30
  Create closure with elements [30..59] + previous closure call
  Update: safeRunEndExclusive = 30, safeRunLen = 30

After chunking: Add remaining [0..29] to result
  safeRunStart = safeRunEndExclusive - safeRunLen = 0
  Add elements [0..29]
```

**Key insight:** `safeRunStart` is RECALCULATED on each iteration. The algorithm is correct.

### When Variables Get IDs

During closure creation in `SubroutineParser.java:704`:
```java
if (ast.id == 0) {
    ast.id = EmitterMethodCreator.classCounter++;
}
```

This assigns IDs to ANY `my` variable that gets captured by a refactored closure, not just BEGIN variables.

## Debugging Techniques

### Command-Line Tools

1. **`--debug`** - Emit debug information during compilation
   ```bash
   ./jperl --debug script.pl
   ```
   Shows: use statements, warnings, compilation stages

2. **`--disassemble`** - Show JVM bytecode
   ```bash
   ./jperl --disassemble script.pl > output.txt
   ```
   Shows: Java classes, methods, bytecode instructions, LINENUMBER markers

3. **`--parse`** - Show AST structure
   ```bash
   ./jperl --parse script.pl
   ```
   Shows: AST nodes, token positions (pos:N)

4. **`--tokenize`** - Show lexer tokens
   ```bash
   ./jperl --tokenize script.pl
   ```
   Shows: Each token with type and position

### Understanding LINENUMBER vs Line Numbers

**Critical distinction:**

1. **`LINENUMBER` in disassembly output** (from `./jperl --disassemble`) is **TOKEN INDEX**, not source line number
   ```
   LINENUMBER 229 in bytecode = Token 229 in tokenizer output
   Use: ./jperl --tokenize file.pl | sed -n '229p'
   ```

2. **"line" in Perl error messages** (runtime errors) is **SOURCE LINE NUMBER**
   ```
   Error: at line 229
   Source line 229: %fileTypeLookup = (

   This IS the actual source line 229 in the file
   ```

**Key insight:** Don't confuse bytecode LINENUMBER (token index) with error message line numbers (source line).

### Adding Debug Statements

**Pattern for tracking execution:**

```java
// In RuntimeHash.java
private static volatile int debugHashId = -1;

public RuntimeArray setFromList(RuntimeList value) {
    if (value.elements.size() == 6 && value.elements.get(1).toString().equals("ExifTool")) {
        long timestamp = System.nanoTime();
        debugHashId = System.identityHashCode(this);
        System.err.println("DEBUG [" + timestamp + "] setFromList CALLED: hash=" +
                          System.identityHashCode(this) + " size=" + this.size());
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = 2; i < Math.min(stack.length, 15); i++) {
            System.err.println("  at " + stack[i]);
        }
    }
    // ... rest of method
}
```

**Use `System.identityHashCode()` to track object identity** (not `.equals()` which might be overridden).

**Use timestamps** to track execution order when multiple events happen.

### Comparing Bytecode Versions

```bash
# Generate disassembly before fix
./jperl --disassemble file.pl > /tmp/before.txt

# Apply fix, rebuild
make

# Generate disassembly after fix
./jperl --disassemble file.pl > /tmp/after.txt

# Compare
diff /tmp/before.txt /tmp/after.txt | less

# Or search for specific patterns
grep "setFromList" /tmp/before.txt
grep "setFromList" /tmp/after.txt
```

### Finding Missing Code

**Pattern: Code appears in source but not in bytecode**

1. Check if refactoring happened:
   ```bash
   grep -c "anon.*apply" disassembly.txt
   ```
   If > 0, code was refactored into closures

2. Search in all anonymous classes:
   ```bash
   grep -B5 -A20 "class org/perlonjava/anon" disassembly.txt | less
   ```

3. Check if code is in a closure that's never called:
   ```bash
   grep "LINENUMBER <token>" disassembly.txt
   ```
   Missing LINENUMBER means code wasn't emitted

## Creating Unit Tests

### Rule: Tests Must Create AST Nodes

**WRONG:**
```perl
# This creates ONE for loop in the AST
for (1..10000) {
    $x++;
}
```

**RIGHT:**
```perl
# This creates 10,000 statements in the AST
$x++;
$x++;
$x++;
# ... repeat 9,997 more times
```

### Generating Large Tests

```bash
cat > test.t << 'EOF'
use v5.38;
use Test::More;

my $x = 0;
EOF

# Generate 10,000 actual statements
perl -e 'print "\$x += 1;\n" x 10000' >> test.t

cat >> test.t << 'EOF'
is($x, 10000, "All statements executed");
done_testing();
EOF
```

### Test Requirements for Refactoring

To trigger `LargeBlockRefactorer`:
- Need > 10,000 statements (or large bytecode size)
- Statements must be at same block level (not in subroutines)
- For `treatAllElementsAsSafe` path: no labels, no last/next/redo/return

### Why Simple Tests Often Don't Reproduce Bugs

1. **Optimization:** Perl optimizes `$x++ for 1..N` to a loop, not N statements
2. **Size threshold:** Need enough bytecode, not just lines of code
3. **Specific patterns:** Bug might require specific statement types or combinations
4. **Timing:** Bug might occur during specific compilation phases

## Things That Seemed Broken But Weren't

### False Lead #1: LargeBlockRefactorer Losing Elements

**What I thought:** Lines 383-386 only add `[safeRunStart..safeRunEndExclusive-1]`, so elements `[0..safeRunStart-1]` are lost.

**Reality:** `safeRunStart = safeRunEndExclusive - safeRunLen` is RECALCULATED on each iteration. After chunking completes, this formula correctly identifies the remaining elements.

**How I found out:**
1. Couldn't create a test case to reproduce the bug
2. Traced through algorithm manually with concrete numbers
3. Realized `safeRunStart` changes on each iteration

**Lesson:** If you can't reproduce a bug with a test, you probably misunderstood the code.

### False Lead #2: Variables With IDs Don't Work With Backslash Operator

**What I thought:** `\%allGroups` where `%allGroups` has an ID doesn't create a proper reference.

**Reality:** The backslash operator (`handleCreateReference`) evaluates the operand (which loads the variable correctly) then calls `createReference()` on it. Works fine.

**How I found out:**
1. Created test cases with `\%hash` where hash has ID
2. All tests passed
3. Examined `EmitOperator.handleCreateReference()` - code is correct

**Lesson:** Test your hypothesis before claiming bugs.

### False Lead #3: ExifTool Bug is About Refactoring

**What I thought:** ExifTool failure is caused by refactorer bug.

**Reality:** Fixed refactorer "bug" (which wasn't a bug), ExifTool still fails with same error.

**How I found out:** Applied fix, ExifTool still broken.

**Lesson:** Don't assume cause based on symptoms. Follow the evidence.

## Key Architecture Insights

### Separation of Concerns

1. **Parser** creates AST (in `org.perlonjava.astnode`)
2. **Refactorer** modifies AST to avoid JVM limits (in `org.perlonjava.astrefactor`)
3. **Emitter** converts AST to bytecode (in `org.perlonjava.codegen`)
4. **Runtime** executes bytecode (in `org.perlonjava.runtime.runtimetypes`)

Each layer doesn't need to know about the others' internals.

### Symbol Tables Are Scoped

Each block/subroutine has its own symbol table:
```java
class ScopedSymbolTable {
    Map<String, SymbolEntry> variableIndex;  // Variables in this scope
    ScopedSymbolTable parent;                 // Outer scope
}
```

Lookup walks up the parent chain until variable is found.

### Two Compilation Strategies

1. **Normal variables:** Compile-time slot allocation, runtime ALOAD/ASTORE
2. **BEGIN variables:** Runtime name-based lookup via GlobalVariable

The second strategy is slower but more flexible (survives refactoring).

## Best Practices for Future Debugging

1. **Always create a minimal test case first** - If you can't reproduce it, you don't understand it
2. **Trust the evidence** - If your "fix" doesn't change behavior, you're fixing the wrong thing
3. **Use the right tools** - --disassemble for bytecode, --parse for AST, --tokenize for positions
4. **Add targeted debug** - Track specific objects with identityHashCode, use timestamps for ordering
5. **Read the code forward AND backward** - Start from error, trace back to cause
6. **Document false leads** - Learn from mistakes, don't repeat them
7. **Test hypotheses** - Write code to verify your theory before claiming bugs

## Resources

- `CLAUDE.md` - Project-specific guidance (READ THIS FIRST)
- `SKILL.md` - This document - debugging skills and architecture knowledge
- Stack traces - JVM line numbers are relative to method start
- ASM library documentation - Understanding JVM bytecode generation
- Source code in `src/main/java/org/perlonjava/` - The actual implementation
