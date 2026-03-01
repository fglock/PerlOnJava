---
name: debug-exiftool
description: Debug and fix Image::ExifTool test failures in PerlOnJava
argument-hint: "[test-name or test-file]"
triggers:
  - user
  - model
---

# Debugging Image::ExifTool Tests in PerlOnJava

You are debugging failures in the Image::ExifTool test suite running under PerlOnJava (a Perl-to-JVM compiler/interpreter). Failures typically stem from missing Perl features or subtle behavior differences in PerlOnJava, not bugs in ExifTool itself.

## Project Layout

- **PerlOnJava source**: `src/main/java/org/perlonjava/` (compiler, bytecode interpreter, runtime)
- **ExifTool distribution**: `Image-ExifTool-13.44/` (unmodified upstream)
- **ExifTool tests**: `Image-ExifTool-13.44/t/*.t`
- **ExifTool test lib**: `Image-ExifTool-13.44/t/TestLib.pm` (exports `check`, `writeCheck`, `writeInfo`, `testCompare`, `binaryCompare`, `testVerbose`, `notOK`, `done`)
- **ExifTool test data**: `Image-ExifTool-13.44/t/images/` (reference images)
- **PerlOnJava unit tests**: `src/test/resources/unit/*.t` (mvn test suite)
- **Fat JAR**: `target/perlonjava-3.0.0.jar`

## Running Tests

### Single ExifTool test
```bash
cd Image-ExifTool-13.44
java -jar ../target/perlonjava-3.0.0.jar -Ilib t/ExifTool.t
```

### Single ExifTool test with timeout (prevents infinite loops)
```bash
cd Image-ExifTool-13.44
timeout 60 java -jar ../target/perlonjava-3.0.0.jar -Ilib t/XMP.t
```

### All ExifTool tests with timeout (batch)
```bash
cd Image-ExifTool-13.44
for t in t/*.t; do
    name=$(basename "$t" .t)
    printf "%-20s " "$name"
    output=$(timeout 60 java -jar ../target/perlonjava-3.0.0.jar -Ilib "$t" 2>&1)
    ec=$?
    if [ $ec -eq 124 ]; then echo "TIMEOUT"
    else
        pass=$(echo "$output" | grep -cE '^ok ')
        fail=$(echo "$output" | grep -cE '^not ok ')
        plan=$(echo "$output" | grep -oE '^1\.\.[0-9]+' | head -1)
        planned=${plan#1..}
        [ $fail -gt 0 ] || [ $ec -ne 0 ] && echo "FAIL (pass=$pass fail=$fail planned=${planned:-?} exit=$ec)" || echo "PASS ($pass/${planned:-?})"
    fi
done
```

### Build the JAR (required after Java source changes)
```bash
mvn package -q -DskipTests
```

### Run PerlOnJava's own test suite (154 tests)
```bash
mvn test
```

## Test File Anatomy

ExifTool `.t` files follow a common pattern:
```perl
BEGIN { $| = 1; print "1..N\n"; require './t/TestLib.pm'; t::TestLib->import(); }
END { print "not ok 1\n" unless $loaded; }
use Image::ExifTool;
$loaded = 1;

# Read test
my $exifTool = Image::ExifTool->new;
my $info = $exifTool->ImageInfo('t/images/SomeFile.ext', @tags);
print 'not ' unless check($exifTool, $info, $testname, $testnum);
print "ok $testnum\n";

# Write test (uses writeInfo from TestLib)
writeInfo($exifTool, 'src.jpg', 'tmp/out.jpg', \@setNewValue_args);
```

The `check()` function compares extracted tags against reference files in `t/ExifTool_N.out` (or `t/<TestName>_N.out`). The `writeInfo()` function calls SetNewValue + WriteInfo and compares the output file.

## Debugging Workflow

1. **Run the failing test** and capture full output (stdout + stderr). Look for:
   - `not ok N` lines (which specific sub-tests fail)
   - Runtime exceptions / stack traces from Java
   - `Can't locate ...` (missing module)
   - `Undefined subroutine` / `Can't call method` errors

2. **Identify the failing sub-test number** and find it in the `.t` file. Map it to the ExifTool operation (read vs write, which image format, which tags).

3. **Check the `.out` reference file** (e.g., `t/XMP_3.out`) to understand expected output. Compare with actual output by adding debug prints or using `testVerbose`.

4. **Isolate the Perl construct** causing the failure. Write a minimal `.pl` reproducer:
   ```bash
   java -jar target/perlonjava-3.0.0.jar -e 'print "test\n"'
   ```

5. **Trace into PerlOnJava source** to find the bug. Key areas:
   - **Bytecode interpreter**: `src/main/java/org/perlonjava/runtime/BytecodeInterpreter.java`
   - **Compiler/emitter**: `src/main/java/org/perlonjava/codegen/`
   - **Runtime operators**: `src/main/java/org/perlonjava/operators/`
   - **Runtime scalars/arrays/hashes**: `src/main/java/org/perlonjava/runtime/RuntimeScalar.java`, `RuntimeArray.java`, `RuntimeHash.java`
   - **IO operations**: `src/main/java/org/perlonjava/runtime/RuntimeIO.java`
   - **String/regex ops**: `src/main/java/org/perlonjava/operators/StringOperators.java`
   - **List operators**: `src/main/java/org/perlonjava/operators/ListOperators.java`
   - **Large block refactoring** (wraps big subs in anonymous sub calls): `src/main/java/org/perlonjava/codegen/LargeBlockRefactorer.java`

6. **Fix in PerlOnJava**, rebuild (`mvn package -q -DskipTests`), re-run the ExifTool test.

7. **Run `mvn test`** to verify no regressions in the 154 unit tests.

## Common Failure Patterns

### Infinite loops / TIMEOUT
- Often caused by `return` inside a block that was refactored by `LargeBlockRefactorer.tryWholeBlockRefactoring()` into `sub { ... }->(@_)`. The `return` exits the anonymous sub instead of the enclosing function. Check `ControlFlowDetectorVisitor.java` for unsafe control flow detection.
- Can also be caused by regex catastrophic backtracking — PerlOnJava has timeout protection via `d0071f45`.

### Foreach loop variable corruption
- After a foreach loop exits, the loop variable register may still alias the last array element. Writes to that variable corrupt the source array. Fixed in `BytecodeInterpreter.java` (`FOREACH_NEXT_OR_EXIT`) and `EmitForeach.java`.

### Write test failures ("WriteInfo errors")
- `SetNewValue` or `WriteInfo` returning errors. Often due to missing Perl features in string/binary operations (pack/unpack edge cases, encoding, tied handles).

### Encoding / binary data issues
- ExifTool heavily uses `binmode`, `sysread`, `syswrite`, `pack`, `unpack`, `Encode::decode`/`encode`. Check that PerlOnJava handles these correctly for the specific format being tested.

### Missing or incomplete Perl builtins
- `local *glob` unwinding, tied handles, `pos()` after regex match, `$1`/`$2` capture variables in eval, `wantarray` in specific contexts.

### Read-only variable violations
- Operations that try to modify read-only scalars (e.g., `$_` aliased to a constant). Check `RuntimeScalarReadOnly` usage.

## Adding Debug Instrumentation

When you need to trace execution inside ExifTool Perl code, add temporary prints:
```perl
print STDERR "DEBUG: variable=$variable\n";
```

When tracing inside PerlOnJava Java code, use:
```java
System.err.println("DEBUG: value=" + value);
```

**Always remove debug instrumentation before committing.**

## Key Files Quick Reference

| Area | File |
|------|------|
| Bytecode interpreter | `src/main/java/org/perlonjava/runtime/BytecodeInterpreter.java` |
| Foreach emission | `src/main/java/org/perlonjava/codegen/EmitForeach.java` |
| Large block refactoring | `src/main/java/org/perlonjava/codegen/LargeBlockRefactorer.java` |
| Control flow safety | `src/main/java/org/perlonjava/codegen/ControlFlowDetectorVisitor.java` |
| Runtime scalar | `src/main/java/org/perlonjava/runtime/RuntimeScalar.java` |
| IO operations | `src/main/java/org/perlonjava/runtime/RuntimeIO.java` |
| String operators | `src/main/java/org/perlonjava/operators/StringOperators.java` |
| List operators | `src/main/java/org/perlonjava/operators/ListOperators.java` |
| Pack/Unpack | `src/main/java/org/perlonjava/operators/PackOperator.java` |
| Eval handling | `src/main/java/org/perlonjava/codegen/EmitterMethodCreator.java` |
| Dynamic variables | `src/main/java/org/perlonjava/runtime/DynamicVariableManager.java` |
