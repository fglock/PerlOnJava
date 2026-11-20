# Plan: Fix `./jperl --interpreter src/test/resources/unit/array.t`

## Problem Summary

The test produces no output in interpreter mode but works in JVM mode. The issue is that Test::More/Test2 output handles are broken in interpreter mode.

## Root Cause Analysis

1. **Test2::Formatter::TAP** gets output handles via `clone_io(Test2::API::test2_stdout())`
2. The cloned GLOB has a **null RuntimeIO** - `fileno()` throws NPE, `print` silently fails
3. The GLOB looks valid (stringifies to `GLOB(0x...)`) but isn't connected to any actual I/O

### Reproduction

```bash
# Works - JVM mode
./jperl src/test/resources/unit/array.t

# Fails - no output in interpreter mode
./jperl --interpreter src/test/resources/unit/array.t

# Minimal reproduction showing broken handle
./jperl --interpreter -e '
use Test2::Formatter::TAP;
my $fmt = Test2::Formatter::TAP->new();
my $h = $fmt->handles->[0];
print $h "TEST\n";  # Silently fails - no output
'
```

## Investigation Findings

| Test | Result |
|------|--------|
| `clone_io(\*STDOUT)` directly in -e code | Works |
| `clone_io()` from Test2::Util module | Works |
| `Test2::API::test2_stdout()` | Returns GLOB but printing fails |
| `Test2::Formatter::TAP->new()->handles` | Returns GLOBs with null RuntimeIO |

The bug appears when the handle passes through Test2::API's storage/retrieval mechanism.

## Hypothesis

The interpreter's handling of GLOB values in hash/object storage loses the RuntimeIO connection. When a GLOB is stored in a hash (like `$self->{handles}`) and later retrieved, the RuntimeIO becomes null.

## Implementation Plan

### Phase 1: Create Failing Unit Test
- [x] Create `dev/sandbox/closure_capture_package_level.t` (done, but passes)
- [ ] Create minimal test that reproduces the GLOB storage bug

### Phase 2: Debug GLOB Storage
1. Add debug logging to track when RuntimeIO becomes null
2. Trace the GLOB through:
   - Creation in `clone_io()`
   - Storage in Test2::API package variable
   - Retrieval via `test2_stdout()`
   - Storage in formatter's `handles` array
   - Retrieval for printing

### Phase 3: Fix the Bug
Location candidates:
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeIO.java` - IO handle management
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeGlob.java` - GLOB storage
- `src/main/java/org/perlonjava/backend/bytecode/BytecodeInterpreter.java` - interpreter operations

### Phase 4: Verify
```bash
./jperl --interpreter src/test/resources/unit/array.t
# Should output: ok 1 - Array has correct length ... 1..52
```

## Files to Investigate

- `RuntimeGlob.java` - How GLOBs store/retrieve RuntimeIO
- `RuntimeIO.java` - IO handle lifecycle
- `BytecodeInterpreter.java` - GLOB operations in interpreter
- `InlineOpcodeHandler.java` / `OpcodeHandlerExtended.java` - GLOB-related opcodes

## Next Steps

1. Add `print STDERR` debugging to trace where RuntimeIO becomes null
2. Compare JVM vs interpreter execution paths for GLOB handling
3. Identify the specific operation that loses the IO connection
