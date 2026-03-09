# Perl Debugger Implementation Skill

## Overview

Continue implementing the Perl debugger (`-d` flag) for PerlOnJava. The debugger uses DEBUG opcodes injected at statement boundaries in the bytecode interpreter.

## Key Documentation

### Design Document
- **Location**: `dev/design/perl_debugger.md`
- Contains implementation phases, architecture diagrams, and code examples

### Perl Debugger Documentation (reference)
- `perldoc perldebug` - User documentation for Perl debugger
- `perldoc perldebguts` - Internal implementation details (key reference!)
- `perldoc perldebtut` - Tutorial
- `perl5/lib/perl5db.pl` - The standard Perl debugger (~10,000 lines)

## Current Implementation Status

**Branch**: `implement-perl-debugger`

### Completed (Phase 1 + partial Phase 2)
- DEBUG opcode (376) in `Opcodes.java`
- `-d` flag in `ArgumentParser.java` sets `debugMode=true`, forces interpreter
- `BytecodeCompiler` emits DEBUG at statement boundaries when `debugMode=true`
- `BytecodeInterpreter` handles DEBUG opcode, calls `DebugHooks.debug()`
- `DebugState.java` - global debug flags, breakpoints, source storage
- `DebugHooks.java` - command loop with n/s/c/q/l/b/B/L/h commands
- Source line extraction from tokens (`ErrorMessageUtil.extractSourceLines()`)
- `l` command shows source with `==>` current line marker
- Compile-time statements (`use`/`no`) correctly skipped via `compileTimeOnly` annotation
- Infrastructure nodes in BEGIN blocks skipped via `skipDebug` annotation

### Working Commands
| Command | Description |
|---------|-------------|
| `n` | Next (step over) |
| `s` | Step into |
| `c [line]` | Continue (optionally to line) |
| `q` | Quit |
| `l [range]` | List source (`l 10-20` or `l 15`) |
| `.` | Show current line |
| `b [line]` | Set breakpoint |
| `B [line]` | Delete breakpoint (`B *` = all) |
| `L` | List breakpoints |
| `h` | Help |

## Comparison with System Perl Debugger

Tested side-by-side with `perl -d`:

| Feature | jperl | System perl | Status |
|---------|-------|-------------|--------|
| Start line | First runtime stmt | First runtime stmt | Match |
| `n` (next) | Works | Works | Match |
| `s` (step) | Works | Works | Match |
| `c` (continue) | Works | Works | Match |
| `b` (breakpoint) | Works, confirms | Works, silent | OK |
| `L` (list bp) | Simple list | Shows code + condition | Different |
| `l` (list) | Shows context around line | Shows current line only | Different |
| `q` (quit) | Works | Works | Match |
| Package prefix | Missing | Shows `main::` | TODO |
| Prompt counter | `DB<0>` (0-indexed) | `DB<1>` (1-indexed) | TODO |
| Loading message | None | Shows perl5db.pl version | OK (intentional) |

### Known Differences to Address
1. **Package prefix**: Add `main::` (or current package) to location display
2. **Prompt counter**: Change to 1-indexed (`DB<1>`) to match Perl
3. **`l` command**: Perl shows current line, subsequent `l` shows next 10 lines

## Source Files

| File | Purpose |
|------|---------|
| `src/main/java/org/perlonjava/runtime/debugger/DebugState.java` | Global flags, breakpoints, source storage |
| `src/main/java/org/perlonjava/runtime/debugger/DebugHooks.java` | Debug hook called by DEBUG opcode, command loop |
| `src/main/java/org/perlonjava/backend/bytecode/Opcodes.java` | DEBUG = 376 |
| `src/main/java/org/perlonjava/backend/bytecode/BytecodeCompiler.java` | Emits DEBUG opcodes, checks `skipDebug` |
| `src/main/java/org/perlonjava/backend/bytecode/BytecodeInterpreter.java` | Handles DEBUG opcode |
| `src/main/java/org/perlonjava/app/cli/ArgumentParser.java` | `-d` flag handling |
| `src/main/java/org/perlonjava/frontend/parser/StatementParser.java` | Marks `use`/`no` as `compileTimeOnly` |
| `src/main/java/org/perlonjava/frontend/parser/SpecialBlockParser.java` | Marks BEGIN infrastructure as `skipDebug` |
| `src/main/java/org/perlonjava/runtime/runtimetypes/ErrorMessageUtil.java` | `extractSourceLines()` for source display |

## Next Steps (from design doc)

### Phase 2: Source Line Support (partially done)
- [x] Store source lines during parsing
- [x] Skip compile-time statements (use/no)
- [ ] Track breakable lines (statements vs comments)
- [ ] Implement `@{"_<$filename"}` magical array
- [ ] Implement `%{"_<$filename"}` for breakpoint storage

### Phase 3: Debug Variables
- [ ] `$DB::single`, `$DB::trace`, `$DB::signal` as tied variables
- [ ] `$DB::filename`, `$DB::line` (currently Java-only)
- [ ] `@DB::args` support in `caller()`
- [ ] `%DB::sub` for subroutine location tracking

### Phase 4: Perl Expression Evaluation
- [ ] `p expr` - print expression value
- [ ] `x expr` - dump expression (Data::Dumper style)
- [ ] General expression evaluation in debugger context

### Phase 5: perl5db.pl Compatibility
- [ ] Inject `BEGIN { require 'perl5db.pl' }` when `-d` used
- [ ] `DB::sub()` routing for subroutine tracing
- [ ] Test with actual perl5db.pl

## Tips for Development

### Testing the debugger
```bash
# Build after changes
mvn package -q -DskipTests

# Test basic stepping
echo 'n
n
q' | ./jperl -d /tmp/test.pl

# Test source listing
echo 'l
l 1-10
q' | ./jperl -d -e 'print 1; print 2; print 3;'

# Test breakpoints
echo 'b 3
c
q' | ./jperl -d /tmp/test.pl

# Compare with system perl
perl -d /tmp/test.pl
```

### Interactive testing
The debugger can be tested interactively - send commands and observe responses.

### Key design principles
1. **All debugger logic in DebugHooks** - interpreter loop stays clean
2. **Zero overhead when not debugging** - no DEBUG opcodes emitted
3. **Breakpoints via Set<String>** - O(1) lookup of "file:line"
4. **Source from tokens** - `ErrorMessageUtil.extractSourceLines()` rebuilds source
5. **Skip internal nodes** - `compileTimeOnly` and `skipDebug` annotations

### Adding new commands
1. Add case in `DebugHooks.executeCommand()`
2. Create `handleXxx()` method
3. Return `true` to resume execution, `false` to stay in command loop
4. Update `handleHelp()` with new command

### Adding debug variables
To expose `$DB::single` etc. to Perl code:
1. Create tied variable class that reads/writes `DebugState` fields
2. Register in GlobalVariable initialization
3. See `GlobalVariable.java` for examples of special variables

### Step-over implementation
Already working via `DebugState.stepOverDepth`:
- `n` sets `stepOverDepth = callDepth`
- DEBUG skips when `callDepth > stepOverDepth`
- Need to call `DebugHooks.enterSubroutine()`/`exitSubroutine()` on sub entry/exit

### Annotations for skipping DEBUG opcodes
- `compileTimeOnly` - skips entire statement compilation (for `use`/`no` results)
- `skipDebug` - skips only DEBUG opcode emission (for infrastructure nodes)

### Common issues
- **Source not showing**: Check `DebugState.sourceLines` is populated
- **Breakpoint not hitting**: Verify line is breakable (has DEBUG opcode)
- **Step-over not working**: Ensure `callDepth` tracking is correct
- **Duplicate lines**: Check for missing `skipDebug` on internal nodes
