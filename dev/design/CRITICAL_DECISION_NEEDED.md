# CRITICAL DECISION: Control Flow Architecture

## Current Status

**Pass Rate:** 30.4% (massive regression from 99.8% baseline)

**Root Cause:** Tagged return values are created but never processed, causing ALL non-local control flow to error:
- `uni/variables.t`: "Label not found for 'last SKIP'" (66833 tests blocked)
- `op/list.t`: StackOverflowError (3 tests blocked)  
- `op/hash.t`: 5 failures

## The Problem

Tagged return control flow requires **call-site checks** to work:
1. Subroutine call returns marked `RuntimeControlFlowList`
2. **Call site must check** and dispatch to loop handler
3. Loop handler processes control flow (LAST/NEXT/REDO/GOTO)

**BUT:** Call-site checks cause `ArrayIndexOutOfBoundsException` in ASM frame computation.
- Tried: Fixed slot → Dynamic slot → Simplified pattern
- All fail with ASM frame merge errors
- Root cause: `DUP → branch → stack manipulation` breaks ASM's COMPUTE_FRAMES

## Three Options

### Option 1: Fix ASM Frame Computation (High Effort, Uncertain Success)

**Approach:** Manually provide frame hints at every branch point
- Call `mv.visitFrame(F_FULL, ...)` with exact local types and stack types
- Track all local variable types throughout method
- Update frames whenever bytecode changes

**Pros:**
- Pure tagged return solution (no exceptions)
- Clean architecture

**Cons:**
- **Very high effort** - must track types for every local variable
- **Fragile** - breaks if bytecode generation changes
- **Error-prone** - wrong frame = VerifyError  
- **No guarantee it will work** - ASM may still reject complex patterns

**Estimated Time:** 20-40 hours of work, 50% chance of success

### Option 2: Hybrid Approach (Recommended)

**Approach:** Use exceptions ONLY for non-local control flow
- **Local** last/next/redo (same loop): Fast GOTO (current, works ✓)
- **Non-local** last/next/redo (crosses subroutine): Exception-based
- Detect at compile-time: if label not in current method, throw exception

**Pros:**
- **Proven to work** (old approach was at 99.8%)
- No ASM frame issues
- Fast path for common case (local control flow)
- Can implement immediately

**Cons:**
- Uses exceptions (performance cost for non-local flow)
- Mixed architecture (goto + exceptions)

**Implementation:**
1. Add back exception classes (`LastException`, `NextException`, etc.)
2. In `EmitControlFlow`: if label not found → throw exception instead of returning marked list
3. Keep fast GOTO for local control flow
4. Remove `RuntimeControlFlowList` creation for non-local flow

**Estimated Time:** 4-8 hours

### Option 3: Pure Exception-Based (Fallback)

**Approach:** Revert to pure exception-based control flow
- All last/next/redo/goto throw exceptions
- Try-catch blocks around loops
- Stack cleanup before throwing

**Pros:**
- **Proven architecture** (was working before)
- No ASM frame issues
- Simple to understand

**Cons:**
- Higher bytecode size (try-catch blocks)
- "Method too large" errors possible
- Exception overhead even for local flow

**Estimated Time:** 2-4 hours (mostly revert)

## Recommendation

**Option 2 (Hybrid)** is the best path forward:
- Balances performance (fast local, slower non-local)
- Proven to work (exceptions work, local GOTO works)
- Reasonable implementation time
- Avoids ASM frame computation issues entirely

## Test Case That Must Work

```perl
# From uni/variables.t (66833 tests depend on this!)
SKIP: {
    sub { last SKIP }->(); # Non-local last
}

# From op/for.t
OUTER: for (1..3) {
    sub { last OUTER }->(); # Non-local last
}
```

## Existing SKIP Workarounds (TO BE REMOVED)

There are currently THREE workarounds for SKIP blocks that should be removed once proper control flow is working:

1. **AST Transformation** (`src/main/java/org/perlonjava/parser/TestMoreHelper.java`)
   - Transforms `skip()` calls into `skip_internal() && last SKIP`
   - Called from `StatementParser.parseIfStatement()` line 241

2. **Test::More Patch** (`src/main/perl/lib/Test/More.pm`)
   - Protected file (won't be overwritten by sync)
   - Has `skip_internal()` subroutine (lines 296-304)
   - Prints SKIP messages directly instead of using `last SKIP`

3. **Import Configuration** (`dev/import-perl5/config.yaml`)
   - Line 382-384: Test::More.pm marked as `protected: true`
   - Prevents sync from overwriting the patched version

**Once proper control flow works**, these should be removed and we should use the standard Perl5 Test::More.pm.

## Question for User

Which option should we pursue?
1. Option 1 (Fix ASM) - High risk, high effort
2. Option 2 (Hybrid) - **Recommended**
3. Option 3 (Pure exceptions) - Safe fallback

**Note:** User mentioned these SKIP workarounds exist and should be removed once control flow is fixed.

