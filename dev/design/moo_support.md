# Moo Support for PerlOnJava

## Overview

This document describes using [Moo](https://metacpan.org/pod/Moo) as a test case for CPAN integration in PerlOnJava. **Moo is not a goal in itself** - it's being used to verify that:

1. `jcpan` can install CPAN modules correctly
2. `jcpan` can run module tests
3. Complex pure-Perl CPAN modules work correctly in PerlOnJava

**Success criteria: All Moo tests MUST pass.**

## Current Status

**Moo installation**: Successfully installed via `./jcpan Moo`

**Basic loading**: `use Moo;` works correctly

**Class definition**: **WORKS** - all blocking issues have been fixed

```perl
# This now works!
package Person;
use Moo;
has name => (is => "ro");
has age => (is => "rw", default => sub { 0 });
1;

package main;
my $p = Person->new(name => "Alice", age => 30);
print $p->name, " is ", $p->age, "\n";  # Alice is 30
```

**Inheritance with extends**: **WORKS** - parser fix for `@{*{expr}}`

```perl
# This now works!
package Animal;
use Moo;
has name => (is => 'ro');

package Dog;
use Moo;
extends 'Animal';  # Uses @{*{_getglob("${target}::ISA")}} = @_
has breed => (is => 'ro');

my $d = Dog->new(name => 'Rex', breed => 'German Shepherd');
print $d->name, " is a ", $d->breed, "\n";  # Rex is a German Shepherd
```

## Issues Found

### Issue 1: Parser Bug with `x =>` Syntax (FIXED)

**Symptom**: 
```perl
package Point;
use Moo;
has x => (is => "ro");  # Was: Syntax error!
```

**Error**: `syntax error at ... near "(is => "` or `Too many arguments`

**Root cause**: Two parser issues:
1. In `ListParser.looksLikeEmptyList()`, `x` (which is in `INFIX_OP` as the repetition operator) followed by `=>` was incorrectly treated as an empty list
2. In `Parser.parseExpression()`, `x=` followed by `>` wasn't recognized as fat comma autoquoting

**Solution**:
1. Added special case in `ListParser.java` (line 355-360):
   ```java
   } else if (token.text.equals("x") && nextToken.text.equals("=>")) {
       // Special case: `x =>` is autoquoted as bareword, not the repetition operator
       // This is critical for Moo which uses hash keys like: x => 1
   ```
2. Added check in `Parser.java` (lines 181-188):
   ```java
   if (tokens.get(tokenIndex + 2).text.equals(">")) {
       break; // Stop parsing infix, let 'x' be parsed as a bareword argument
   }
   ```

**Files changed**:
- `src/main/java/org/perlonjava/frontend/parser/ListParser.java`
- `src/main/java/org/perlonjava/frontend/parser/Parser.java`

### Issue 2: Incomplete Java-based Carp Module (FIXED)

**Symptom**:
```perl
package Point;
use Moo;
has("x", (is => "ro"));  # Uses parentheses to avoid Issue 1
```

**Error**: `Undefined subroutine &Carp::short_error_loc called at .../Moo.pm line 262`

**Root cause**: The Java-based `Carp.java` implements only basic functions. Real CPAN modules like Moo need advanced Carp functions like `short_error_loc`.

**Solution**: Replaced Java-based Carp with Perl's Carp.pm from perl5/dist/Carp/

**Files changed**:
- Deleted `src/main/java/org/perlonjava/runtime/perlmodule/Carp.java`
- Added `src/main/perl/lib/Carp.pm` (via sync.pl)
- Added `src/main/perl/lib/Carp/Heavy.pm` (via sync.pl)
- Updated `dev/import-perl5/config.yaml`
- Updated `src/main/java/org/perlonjava/runtime/perlmodule/DBI.java` (removed Carp dependency)

### Issue 3: String Interpolation Bug with `@;` (FIXED)

**Symptom**:
```perl
my $x = "\$@;";
print "[$x]\n";  # PerlOnJava: [$]  Perl: [$@;]
```

**Root cause**: The string interpolation code was treating `@;` as an array variable, when `;` is not a valid identifier character for arrays.

**Solution**: Added `isValidArrayVariableStart()` method in `StringSegmentParser.java` that only allows valid array variable characters (`{`, `$`, `+`, `-`, `_`, `^`, identifiers, numbers) after `@` sigil.

**File changed**: `src/main/java/org/perlonjava/frontend/parser/StringSegmentParser.java`

### Issue 4: Method::Generate::Constructor->new() returns undef (FIXED)

**Symptom**:
```perl
package Point;
use Moo;
has("x", (is => "ro"));
```

**Error**: `Can't call method "install_delayed" on an undefined value at Moo.pm line 119`

**Root cause**: The `goto &$coderef` construct in Method::Generate::Constructor was not properly returning the result in the JVM backend. The TAILCALL marker wasn't being handled at the call site for method calls.

**Solution**: Added TAILCALL trampoline handling in `Dereference.java` for method calls:
- When `RuntimeCode.callCached()` returns a TAILCALL marker, the code now loops and executes the tail call at the call site
- Made `EmitSubroutine.emitBlockDispatcher()` package-visible so it can be reused

**Files changed**:
- `src/main/java/org/perlonjava/backend/jvm/Dereference.java` - Added TAILCALL trampoline (lines 768-897)
- `src/main/java/org/perlonjava/backend/jvm/EmitSubroutine.java` - Made emitBlockDispatcher() package-visible

### Issue 5: Parser Bug with `@{*{expr}}` Glob Dereference (FIXED)

**Symptom**:
```perl
package Dog;
use Moo;
extends 'Animal';  # FAILS - extends uses @{*{_getglob(...)}}
```

**Error**: `@{*{expr}}` was parsed as hash slice on `@*` instead of array dereference of glob dereference.

**Root cause**: Two parser issues:
1. In `IdentifierParser.parseComplexIdentifierInner()`, `*` followed by `{` inside braces was being treated as special variable `$*` followed by subscript
2. In `Variable.parseBracedVariable()`, the unwrapping logic for `${*F}` was incorrectly also unwrapping `${*{expr}}`

**Solution**:
1. Added check in `IdentifierParser.java` (lines 202-209):
   ```java
   // Special case: * followed by { is glob dereference when inside braces
   // @{*{expr}} should be parsed as @{ *{expr} }, not @*{expr} (hash slice on @*)
   if (insideBraces && firstChar == '*' && nextToken.text.equals("{")) {
       return null; // Force fallback to expression parsing for glob dereference
   }
   ```
2. Modified `Variable.java` (lines 876-887) to only unwrap `*` operator when operand is IdentifierNode (for `${*F}`), not when it's a complex expression like `*{expr}`

**Files changed**:
- `src/main/java/org/perlonjava/frontend/parser/IdentifierParser.java`
- `src/main/java/org/perlonjava/frontend/parser/Variable.java`

### Issue 6: Internals::stack_refcounted() Not Implemented (FIXED)

**Symptom**: op/array.t tests 136-199 would crash with OutOfMemoryError

**Root cause**: `Internals::stack_refcounted()` returned undef, causing test at line 509 to try to set array length to a huge number (the numeric value of a reference pointer).

**Solution**: Implemented `stack_refcounted()` to return 1, indicating reference-counted stack behavior (appropriate for Java's GC).

**File changed**: `src/main/java/org/perlonjava/runtime/perlmodule/Internals.java`

## Solution Plan

### Phase 1: Replace Java-based Carp with Perl's Carp.pm ✓ COMPLETE

- Added Carp.pm to sync.pl config
- Ran sync.pl to import Carp.pm and Carp/Heavy.pm
- Deleted Carp.java
- Updated DBI.java to use WarnDie directly instead of Carp

### Phase 2: Fix String Interpolation Bug ✓ COMPLETE

- Added `isValidArrayVariableStart()` method to properly distinguish `@;` (not interpolated) from `$/` (interpolated)

### Phase 3: Fix goto &$coderef in JVM Backend ✓ COMPLETE

- Added TAILCALL trampoline in `Dereference.java` for method calls
- When a method call returns a TAILCALL marker, the trampoline loop executes the tail call at the call site
- This fixed `Method::Generate::Constructor->new()` returning undef

### Phase 4: Fix Parser Bug with `x =>` ✓ COMPLETE

**Location**: `src/main/java/org/perlonjava/frontend/parser/`

**Perl's rule**: Any bareword immediately before `=>` is autoquoted as a string.

**Fix applied**:
1. In `ListParser.looksLikeEmptyList()` - Added check for `x` followed by `=>` to not treat as empty list
2. In `Parser.parseExpression()` - Added check for `x=` followed by `>` to stop infix parsing

### Phase 5: Test Moo End-to-End ✓ COMPLETE

**Test script**:
```perl
#!/usr/bin/env perl
use strict;
use warnings;

package Point;
use Moo;

has x => (is => 'ro', default => 0);
has y => (is => 'ro', default => 0);

sub describe {
    my $self = shift;
    return "Point(" . $self->x . ", " . $self->y . ")";
}

package main;

my $p1 = Point->new(x => 3, y => 4);
print $p1->describe, "\n";       # Point(3, 4)
print "x=", $p1->x, "\n";        # x=3
print "y=", $p1->y, "\n";        # y=4

my $p2 = Point->new();
print $p2->describe, "\n";       # Point(0, 0)

print "All tests passed!\n";
```

### Phase 6: Fix jcpan and Storable YAML limit ✓ COMPLETE

- Fixed jcpan Unix wrapper to use standard cpan script
- Fixed Storable.java codePointLimit (was 3MB, now 50MB)

### Phase 7: Fix Parser Bug with `@{*{expr}}` ✓ COMPLETE

- Fixed `IdentifierParser.java` to return null for `*{` inside braces (forces expression parsing)
- Fixed `Variable.java` to only unwrap `*` for IdentifierNode operands
- This enables Moo's `extends` keyword which uses `@{*{_getglob("${target}::ISA")}} = @_`

### Phase 8: Implement Internals::stack_refcounted ✓ COMPLETE

- Implemented to return 1 (reference-counted stack behavior)
- Fixed op/array.t from 116 to 175 passing tests

## Files Modified

### Phase 1 (Carp) - DONE
- `dev/import-perl5/config.yaml` - Added Carp.pm import
- `src/main/java/org/perlonjava/runtime/perlmodule/Carp.java` - DELETED
- `src/main/java/org/perlonjava/runtime/perlmodule/DBI.java` - Removed Carp dependency
- `src/main/perl/lib/Carp.pm` - New file (from perl5/dist/Carp/lib/)
- `src/main/perl/lib/Carp/Heavy.pm` - New file (from perl5/dist/Carp/lib/)

### Phase 2 (String Interpolation) - DONE
- `src/main/java/org/perlonjava/frontend/parser/StringSegmentParser.java` - Added isValidArrayVariableStart()

### Phase 3 (Constructor Debug) - DONE
- `src/main/java/org/perlonjava/backend/jvm/Dereference.java` - Added TAILCALL trampoline

### Phase 4 (Parser x =>) - DONE
- `src/main/java/org/perlonjava/frontend/parser/ListParser.java`
- `src/main/java/org/perlonjava/frontend/parser/Parser.java`

### Phase 7 (Parser @{*{expr}}) - DONE
- `src/main/java/org/perlonjava/frontend/parser/IdentifierParser.java`
- `src/main/java/org/perlonjava/frontend/parser/Variable.java`

### Phase 8 (Internals) - DONE
- `src/main/java/org/perlonjava/runtime/perlmodule/Internals.java`

## Dependencies

Moo's dependency tree (installed via jcpan):
- Moo
  - Moo::_Utils
  - Moo::Role
  - Method::Generate::Accessor
  - Method::Generate::Constructor ✓ (fixed in Phase 3)
  - Method::Generate::BuildAll
  - Method::Generate::DemolishAll
  - Role::Tiny
  - Sub::Quote
  - Sub::Defer
  - Carp ✓ (now using Perl version)
  - Exporter (Java version works)
  - Scalar::Util (Java version works)

## Test Results (Baseline Verification)

All tests meet or exceed the baseline (20260312T075000):

| Test | Baseline | Current | Status |
|------|----------|---------|--------|
| re/regexp.t | 1786 | 1786 | ✓ |
| op/array.t | 172 | 175 | ✓ (+3 bonus) |
| op/chop.t | 137 | 137 | ✓ |
| op/concat2.t | 3 | 3 | ✓ |
| op/magic.t | 170 | 170 | ✓ |

## Success Criteria

1. `jcpan -t Moo` runs Moo tests ✓ (tests now run with Test::Harness)
2. **All Moo tests pass** ❌ (687/~800 passing, see Known Issues below)
3. `jperl -e 'use Moo; print "OK\n"'` works ✓
4. `has x => (is => "ro")` syntax parses correctly ✓
5. Moo class with attributes works ✓
6. `croak` and `carp` work with proper stack traces ✓
7. `extends 'Parent'` inheritance works ✓ (fixed in Phase 7)
8. No regressions in baseline tests ✓

## Known Issues (Remaining Moo Test Failures)

### Issue: DEMOLISH Not Being Called (Expected - Not Supported)
**Tests affected**: t/demolish-basics.t (3 failures)
**Symptom**: Object destructors (DEMOLISH methods) are not called when objects go out of scope
**Root cause**: DESTROY/fork/threads are not supported in PerlOnJava (they compile but throw at runtime)
**Status**: Expected failure - these features are out of scope for PerlOnJava

### Issue: SUPER::new Not Working in Extended Classes
**Tests affected**: t/extends-non-moo.t
**Symptom**: `Undefined subroutine &Package::SUPER::new called`
**Root cause**: SUPER:: resolution issue when extending non-Moo classes
**Status**: Needs investigation

### Issue: Regex Escaping in Error Messages
**Tests affected**: t/accessor-coerce.t (1 failure)
**Symptom**: `plus\_three` vs `plus_three` - underscores being escaped
**Root cause**: quotemeta or error message formatting escaping `_` incorrectly
**Status**: Minor - cosmetic issue in error messages

### Issue: Role Application Error Messages
**Tests affected**: t/compose-roles.t (4 failures)  
**Symptom**: Missing error messages when required attributes are not provided
**Root cause**: Error throwing in role composition may not propagate correctly
**Status**: Needs investigation

## Remaining jcpan Improvements

### Completed in This Session
- [x] **Version parsing**: Handle "undef" version strings gracefully
- [x] **MM->parse_version**: ExtUtils::MakeMaker now loads ExtUtils::MM
- [x] **Sub::Util**: Java implementation with set_subname (required by Moo)
- [x] **Scalar/List::Util VERSION**: Added $VERSION for CPAN detection
- [x] **Test::Harness**: Added for `make test` support

### Still Needed
- [ ] **Prototype checking**: `$$` prototype with `@array` argument should work (workaround: removed prototype)
- [ ] **CPAN.pm metadata caching**: Reduce repeated dependency checks
- [ ] **Better XS module detection**: Skip XS modules earlier in the process
- [ ] **CPAN::DistnameInfo**: Install to avoid "allow_installing_outdated_dists" warnings

## Progress Tracking

### Current Status: 🟢 WORKING - Tests running, ~85% passing

Moo tests now run via `jcpan -t Moo`. Approximately 687 of ~800 tests pass.
Main blockers: DEMOLISH (destructors), SUPER::new resolution, error message escaping.

### Completed Phases
- [x] Phase 1: Replace Carp.java with Carp.pm (2024-03-14)
  - Imported Carp.pm via sync.pl
  - Deleted Carp.java
  - Fixed DBI.java dependency
- [x] Phase 2: Fix @; string interpolation bug (2024-03-14)
  - Added isValidArrayVariableStart() method
- [x] Phase 3: Fix goto &$coderef in JVM backend (2024-03-14)
  - Added TAILCALL trampoline in Dereference.java
  - Fixed Method::Generate::Constructor->new() returning undef
- [x] Phase 4: Fix parser bug with `x =>` (2024-03-14)
  - Fixed ListParser.looksLikeEmptyList() to handle `x =>`
  - Fixed Parser.parseExpression() to handle `x=` + `>` as fat comma
- [x] Phase 5: Test Moo end-to-end (2024-03-14)
  - All Moo features working: has, ro, rw, default, new
- [x] Phase 6: Fix jcpan and Storable YAML limit (2024-03-14)
  - Fixed jcpan Unix wrapper to use standard cpan script
  - Fixed Storable.java codePointLimit (was 3MB, now 50MB)
- [x] Phase 7: Fix parser bug with `@{*{expr}}` (2024-03-15)
  - Fixed IdentifierParser.java glob dereference detection
  - Fixed Variable.java to preserve *{expr} for complex expressions
  - Enables Moo's extends keyword
- [x] Phase 8: Implement Internals::stack_refcounted (2024-03-15)
  - Returns 1 for RC stack behavior
  - Fixed op/array.t: 116 → 175 passing tests
- [x] Phase 9: Fix goto &sub in use/import (2024-03-15)
  - Added TAILCALL trampoline in StatementParser.parseUseDeclaration()
  - Moo::Role now correctly exports has, with, requires
  - Moo test pass rate: 591 → 687 tests (+96)
- [x] Phase 10: jcpan Test::Harness integration (2024-03-15)
  - Added Test::Harness and TAP:: modules
  - Fixed version parsing for "undef" strings
  - Fixed MM->parse_version() via ExtUtils::MM loading
  - Added Sub::Util Java implementation (set_subname)
  - Added Scalar/List::Util $VERSION for CPAN detection
  - XSLoader stubs for .pm file version detection

### Next Steps

1. **Fix SUPER::new resolution** - Extending non-Moo classes fails
2. **Fix regex escaping** - `\_` vs `_` in error messages
3. **Prototype checking** - `$$` prototype should accept `@array` argument

### PR Information
- **Branch**: `feature/moo-support` (PR #319 - merged)
- **Branch**: `fix/goto-tailcall-import` (PR #320 - open)
- **Key commits**:
  - `7a76739b8` - Fix goto &sub in use/import TAILCALL handling
  - `053d91a95` - Add Sub::Util, fix Scalar/List::Util VERSION, add Test::Harness
  - `7993ef74d` - Fix version parsing and MM->parse_version for CPAN.pm

## Related Documents

- `dev/design/cpan_client.md` - jcpan implementation
- `dev/import-perl5/README.md` - Module sync process
- `dev/import-perl5/config.yaml` - Module import configuration
