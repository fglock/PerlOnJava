# Interpreter Phase 1 Implementation Status

## Summary

Phase 1 core interpreter foundation is mostly complete. The architecture is sound,
but needs API corrections to compile.

## ✅ What's Working

1. **Opcodes.java** - Complete instruction set (140+ opcodes) ✅
2. **InterpretedCode.java** - RuntimeCode subclass ✅
3. **BytecodeInterpreter.java** - Switch-based execution engine (50+ opcodes implemented) ✅
4. **BytecodeCompiler.java** - AST visitor to generate bytecode (WIP) 🚧
5. **InterpreterTest.java** - Test harness (WIP) 🚧
6. **Directory structure** - `dev/interpreter/{architecture,tests}/` ✅

## 🚧 What Needs Fixing

### BytecodeCompiler API Corrections

1. **AST Node Fields**:
   - `NumberNode.value` is String (need to parse to int/double)
   - `StringNode.value` is String ✅
   - `IdentifierNode.name` (NOT `.value`)
   - `OperatorNode` structure needs investigation

2. **Parser Creation**:
   ```java
   // Current (wrong):
   Parser parser = new Parser(perlCode);

   // Correct:
   Lexer lexer = new Lexer(perlCode, "eval.pl");
   List<LexerToken> tokens = lexer.tokenize();
   EmitterContext ctx = new EmitterContext(/* ... */);
   Parser parser = new Parser(ctx, tokens);
   Node ast = parser.parse();
   ```

3. **Visitor Interface**:
   - Need to implement ALL visit() methods from Visitor interface
   - Currently has name clashes (type erasure issues)
   - Check `src/main/java/org/perlonjava/astvisitor/Visitor.java` for complete list

### InterpreterTest Fixes

- Use correct Parser API (Lexer → tokens → Parser)
- Create proper EmitterContext
- Handle parse errors gracefully

## 📋 Next Steps (Recommended Order)

### Step 1: Fix BytecodeCompiler to Compile

1. Check `Visitor.java` interface for all required methods
2. Fix `NumberNode.value` parsing (String → int/double)
3. Fix `IdentifierNode.name` (not `.value`)
4. Check `OperatorNode` structure (likely has `.operand` not `.operands`)
5. Remove duplicate/incorrect visit() methods

### Step 2: Fix InterpreterTest

1. Use correct Parser API:
   ```java
   Lexer lexer = new Lexer(code, "test.pl");
   List<LexerToken> tokens = lexer.tokenize();
   // Create minimal EmitterContext for parsing only
   Parser parser = new Parser(ctx, tokens);
   ```

### Step 3: First Working Test

Follow incremental approach:
1. Disassemble: `./jperl --disassemble -E 'my $x = 5; say $x'`
2. Implement opcodes for that pattern
3. Write test, run, debug
4. Repeat with more complex code

## 🎯 Goal for Next Session

Get `InterpreterTest.main()` running successfully:
```bash
java -cp build/classes/java/main org.perlonjava.interpreter.InterpreterTest

Expected output:
=== Interpreter Test Suite ===

Test 1: my $x = 5; say $x
5

Test 2: my $x = 10 + 20; say $x
30

Test 3: my $x = 'Hello' . ' World'; say $x
Hello World

=== All tests completed ===
```

## 📚 Reference Files

- Visitor interface: `src/main/java/org/perlonjava/astvisitor/Visitor.java`
- EmitterVisitor (example): `src/main/java/org/perlonjava/astvisitor/EmitterVisitor.java`
- Parser usage: `src/main/java/org/perlonjava/scriptengine/PerlLanguageProvider.java`
- AST nodes: `src/main/java/org/perlonjava/astnode/*.java`

## 🔍 Debugging Strategy

Use disassembly to guide implementation:

```bash
# See what the compiler generates
./jperl --disassemble -E 'CODE' 2>&1 | grep -A 50 "LINENUMBER"

# Map to interpreter opcodes
# Example: INVOKESTATIC RuntimeScalarCache.getScalarInt → LOAD_INT opcode
```

## ⏰ Time Estimate

- Fixing BytecodeCompiler API: 30-60 minutes
- Fixing InterpreterTest: 15-30 minutes
- First working test: 15-30 minutes
- **Total: 1-2 hours**

Then ready for incremental opcode implementation following disassembly.
