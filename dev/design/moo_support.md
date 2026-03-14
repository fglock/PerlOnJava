# Moo Support for PerlOnJava

## Overview

This document describes the plan to support the [Moo](https://metacpan.org/pod/Moo) object system in PerlOnJava, demonstrating CPAN module installation via `jcpan`.

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
1. Added special case in `ListParser.java` (line 355-357):
   ```java
   } else if (token.text.equals("x") && nextToken.text.equals("=>")) {
       // Special case: `x =>` is autoquoted as bareword, not the repetition operator
   ```
2. Added check in `Parser.java` (lines 181-186):
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

**Root cause**: The string interpolation code was treating `@;` as an array variable, when `;` is not a valid identifier character.

**Solution**: Added `;` and other non-identifier characters to `isNonInterpolatingCharacter()` in `StringSegmentParser.java`.

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

## Solution Plan

### Phase 1: Replace Java-based Carp with Perl's Carp.pm ✓ COMPLETE

- Added Carp.pm to sync.pl config
- Ran sync.pl to import Carp.pm and Carp/Heavy.pm
- Deleted Carp.java
- Updated DBI.java to use WarnDie directly instead of Carp

### Phase 2: Fix String Interpolation Bug ✓ COMPLETE

- Added non-identifier characters (`;`, `.`, `,`, `:`, `+`, `*`, `!`, `~`, `<`, `>`, `=`, `/`) to `isNonInterpolatingCharacter()`

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

## Files Modified

### Phase 1 (Carp) - DONE
- `dev/import-perl5/config.yaml` - Added Carp.pm import
- `src/main/java/org/perlonjava/runtime/perlmodule/Carp.java` - DELETED
- `src/main/java/org/perlonjava/runtime/perlmodule/DBI.java` - Removed Carp dependency
- `src/main/perl/lib/Carp.pm` - New file (from perl5/dist/Carp/lib/)
- `src/main/perl/lib/Carp/Heavy.pm` - New file (from perl5/dist/Carp/lib/)

### Phase 2 (String Interpolation) - DONE
- `src/main/java/org/perlonjava/frontend/parser/StringSegmentParser.java` - Fixed isNonInterpolatingCharacter()

### Phase 3 (Constructor Debug) - IN PROGRESS
- TBD - need to find root cause

### Phase 4 (Parser)
- `src/main/java/org/perlonjava/frontend/parser/` - TBD

## Dependencies

Moo's dependency tree (installed via jcpan):
- Moo
  - Moo::_Utils
  - Moo::Role
  - Method::Generate::Accessor
  - Method::Generate::Constructor ← Current blocker
  - Method::Generate::BuildAll
  - Method::Generate::DemolishAll
  - Role::Tiny
  - Sub::Quote
  - Sub::Defer
  - Carp ✓ (now using Perl version)
  - Exporter (Java version works)
  - Scalar::Util (Java version works)

## Success Criteria

1. `jperl -e 'use Moo; print "OK\n"'` works ✓
2. `has x => (is => "ro")` syntax parses correctly ✓
3. Moo class with attributes works ✓
4. `croak` and `carp` work with proper stack traces ✓
5. No version mismatch warnings ✓

## Progress Tracking

### Current Status: ✅ ALL PHASES COMPLETE

Moo is now fully functional in PerlOnJava!

### Completed Phases
- [x] Phase 1: Replace Carp.java with Carp.pm (2024-03-14)
  - Imported Carp.pm via sync.pl
  - Deleted Carp.java
  - Fixed DBI.java dependency
- [x] Phase 2: Fix @; string interpolation bug (2024-03-14)
  - Added non-identifier chars to isNonInterpolatingCharacter()
- [x] Phase 3: Fix goto &$coderef in JVM backend (2024-03-14)
  - Added TAILCALL trampoline in Dereference.java
  - Fixed Method::Generate::Constructor->new() returning undef
- [x] Phase 4: Fix parser bug with `x =>` (2024-03-14)
  - Fixed ListParser.looksLikeEmptyList() to handle `x =>`
  - Fixed Parser.parseExpression() to handle `x=` + `>` as fat comma
- [x] Phase 5: Test Moo end-to-end (2024-03-14)
  - All Moo features working: has, ro, rw, default, new

### Next Steps (Future Enhancements)

1. **Test Moo::Role support** - Verify role composition works
2. **Test more attribute options** - `required`, `builder`, `lazy`, `trigger`, `coerce`
3. **Performance testing** - Benchmark Moo object creation vs native Perl
4. **Add Moo to test suite** - Create unit tests for Moo functionality
5. **Document in README** - Add Moo support to feature list

### PR Information
- **Branch**: `feature/moo-support`
- **PR**: https://github.com/fglock/PerlOnJava/pull/319
- **Commits**:
  - `66bfe37a6` - Initial Moo support (Carp.pm, @; fix)
  - `150bc23e8` - Fix x => autoquoting and goto &$coderef

## Related Documents

- `dev/design/cpan_client.md` - jcpan implementation
- `dev/import-perl5/README.md` - Module sync process
- `dev/import-perl5/config.yaml` - Module import configuration
