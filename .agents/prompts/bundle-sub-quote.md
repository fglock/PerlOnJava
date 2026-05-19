# Task: Bundle Modified Sub::Defer/Sub::Quote for PerlOnJava

## Objective
Create bundled modified versions of Sub::Defer and Sub::Quote that pass as many tests as possible by modifying the modules and their tests to skip OOM-triggering cleanup. The goal is to make these modules work correctly on PerlOnJava without requiring a major system redesign of the selective refcounting system.

## Context
Sub::Defer and Sub::Quote are CPAN modules that use weak references extensively. On PerlOnJava, the reachability walker's GC forcing mechanism causes OutOfMemoryError during cleanup when running sub-defer.t and sub-quote.t tests. The tests pass all subtests but timeout during program exit/cleanup.

## Current Test Status
- leaks.t: 9 tests - currently failing due to weak refs to CODE objects not being cleared
- quotify.t: 2595 tests - should pass
- hints.t: 9 tests - 2 failing due to known limitation with caller(0) returning empty warning bits in main script context
- sub-defer.t: 33 tests - timeout/OOM during cleanup
- sub-quote.t: 51 tests - timeout/OOM during cleanup

## Approach
1. Fork Sub::Defer and Sub::Quote into `src/main/perl/lib/Sub/Defer.pm` and `src/main/perl/lib/Sub/Quote.pm`
2. Modify the modules to avoid OOM-triggering cleanup while maintaining compatibility
3. Copy and modify test files to `src/test/resources/modules/Sub-Quote/t/` to skip problematic cleanup
4. Add @INC manipulation to prefer bundled versions over CPAN
5. Test the bundled versions to maximize test pass rate

## Specific Instructions

### 1. Create Bundled Module Files
- Create `src/main/perl/lib/Sub/Defer.pm` based on the CPAN version
- Create `src/main/perl/lib/Sub/Quote.pm` based on the CPAN version
- Add a header comment indicating this is a PerlOnJava-tuned version
- Document the differences from upstream CPAN versions

### 2. Modify Modules to Avoid OOM
Focus on the CLONE method and weak reference usage:

**For Sub::Quote:**
- Modify the CLONE method to use explicit cleanup instead of relying on weak refs being cleared automatically
- Consider using a different data structure for %QUOTED (e.g., refaddr-based keys without weak refs)
- Add a mechanism to skip CLONE cleanup when running tests (e.g., check for environment variable)
- Ensure the CLONE method still works correctly for normal usage (non-test scenarios)

**For Sub::Defer:**
- Apply similar modifications to CLONE and %deferred_info
- Ensure compatibility with Sub::Quote since Sub::Defer depends on it

### 3. Modify Test Files
- Copy test files from the CPAN build directory to `src/test/resources/modules/Sub-Quote/t/`
- Modify sub-defer.t and sub-quote.t to skip or modify tests that trigger OOM
- Add a BEGIN block to set an environment variable (e.g., `$ENV{PERLONJAVA_SKIP_CLONE_CLEANUP} = 1`) to trigger the test-mode behavior
- Keep as many tests as possible - only modify the specific tests that cause OOM
- Document which tests were modified and why

### 4. Add @INC Manipulation
- Modify the module files to add the bundled lib directory to @INC at load time
- Ensure bundled versions take precedence over CPAN versions
- This can be done with:
  ```perl
  use lib '/path/to/PerlOnJava/src/main/perl/lib';
  ```

### 5. Test and Iterate
- Run the bundled module tests to verify they pass
- Run the original CPAN tests to compare behavior
- Iterate on modifications to maximize test pass rate
- Ensure the bundled versions work correctly for normal usage (not just tests)

## Success Criteria
- leaks.t: All 9 tests pass (or as many as possible)
- quotify.t: All 2595 tests pass
- hints.t: As many tests as possible (may still have 2 failing due to caller(0) limitation)
- sub-defer.t: As many tests as possible without OOM/timeout
- sub-quote.t: As many tests as possible without OOM/timeout
- Bundled versions maintain compatibility with upstream CPAN versions for normal usage
- Changes are well-documented in code comments

## Files to Create/Modify
- `src/main/perl/lib/Sub/Defer.pm` (new)
- `src/main/perl/lib/Sub/Quote.pm` (new)
- `src/test/resources/modules/Sub-Quote/t/leaks.t` (new, modified)
- `src/test/resources/modules/Sub-Quote/t/quotify.t` (new)
- `src/test/resources/modules/Sub-Quote/t/hints.t` (new, modified if needed)
- `src/test/resources/modules/Sub-Quote/t/sub-defer.t` (new, modified)
- `src/test/resources/modules/Sub-Quote/t/sub-quote.t` (new, modified)

## Notes
- The CPAN version is currently at `/Users/fglock/.perlonjava/cpan/build/Sub-Quote-2.006009-3/`
- Sub::Defer is a dependency of Sub::Quote and is in the same directory
- Focus on practical solutions that work rather than perfect compatibility
- The goal is to maximize test pass rate, not necessarily pass 100% of tests
- Document any behavioral differences from upstream versions
