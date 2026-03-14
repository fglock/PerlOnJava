# Moo Support for PerlOnJava

## Overview

This document describes the plan to support the [Moo](https://metacpan.org/pod/Moo) object system in PerlOnJava, demonstrating CPAN module installation via `jcpan`.

## Current Status

**Moo installation**: Successfully installed via `./jcpan Moo`

**Basic loading**: `use Moo;` works correctly

**Class definition**: **FAILS** - multiple issues discovered and being fixed

## Issues Found

### Issue 1: Parser Bug with `x =>` Syntax (PENDING)

**Symptom**: 
```perl
package Point;
use Moo;
has x => (is => "ro");  # Syntax error!
```

**Error**: `syntax error at ... near "(is => "`

**Root cause**: The parser treats `x` as the string repetition operator instead of autoquoting it as a bareword before `=>`.

**Verification**:
```bash
# Works (other barewords):
jperl -e 'sub foo { print "@_\n" } foo name => 1;'  # Output: name 1

# Fails (x specifically):
jperl -e 'sub foo { print "@_\n" } foo x => 1;'    # Syntax error

# Standard Perl works:
perl -e 'sub foo { print "@_\n" } foo x => 1;'     # Output: x 1
```

**Affected barewords**: `x`, and potentially `y` (tr operator), `q`, `qq`, `qw`, `qx`, `qr`, `m`, `s`, `tr` when used in similar contexts.

**Workaround**: Use parentheses: `has("x", (is => "ro"))`

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

### Issue 4: Method::Generate::Constructor->new() returns undef (INVESTIGATING)

**Symptom**:
```perl
package Point;
use Moo;
has("x", (is => "ro"));
```

**Error**: `Can't call method "install_delayed" on an undefined value at Moo.pm line 119`

**Root cause investigation**:
```perl
use Method::Generate::Constructor;
my $obj = Method::Generate::Constructor->new(package => "Test", accessor_generator => undef);
print ref($obj);  # prints nothing - $obj is undef!
```

The `new` method in Method::Generate::Constructor does:
```perl
sub new {
  my $class = shift;
  delete _getstash(__PACKAGE__)->{new};
  bless $class->BUILDARGS(@_), $class;
}
```

`BUILDARGS` returns a valid hashref, but `bless` appears to return undef. This might be:
1. A bug in `bless` with certain arguments
2. An issue with the stash manipulation (`delete _getstash(__PACKAGE__)->{new}`)
3. Something else in the bootstrapping process

**Next step**: Debug why `bless` returns undef in this context.

## Solution Plan

### Phase 1: Replace Java-based Carp with Perl's Carp.pm ✓ COMPLETE

- Added Carp.pm to sync.pl config
- Ran sync.pl to import Carp.pm and Carp/Heavy.pm
- Deleted Carp.java
- Updated DBI.java to use WarnDie directly instead of Carp

### Phase 2: Fix String Interpolation Bug ✓ COMPLETE

- Added non-identifier characters (`;`, `.`, `,`, `:`, `+`, `*`, `!`, `~`, `<`, `>`, `=`, `/`) to `isNonInterpolatingCharacter()`

### Phase 3: Debug Method::Generate::Constructor (IN PROGRESS)

Need to investigate why:
```perl
bless $class->BUILDARGS(@_), $class;
```
returns undef when `BUILDARGS` returns a valid hashref.

### Phase 4: Fix Parser Bug with `x =>`

**Location**: `src/main/java/org/perlonjava/frontend/parser/`

**Perl's rule**: Any bareword immediately before `=>` is autoquoted as a string.

**Steps**:
1. Find where `x` operator parsing happens
2. Add lookahead for `=>` - if present, treat as bareword string instead of operator

### Phase 5: Test Moo End-to-End

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
2. `has x => (is => "ro")` syntax parses correctly (pending Phase 4)
3. Moo class with attributes works (pending Phase 3)
4. `croak` and `carp` work with proper stack traces ✓
5. No version mismatch warnings ✓

## Progress Tracking

### Current Status: Phase 3 in progress

### Completed Phases
- [x] Phase 1: Replace Carp.java with Carp.pm (2024-03-14)
  - Imported Carp.pm via sync.pl
  - Deleted Carp.java
  - Fixed DBI.java dependency
- [x] Phase 2: Fix @; string interpolation bug (2024-03-14)
  - Added non-identifier chars to isNonInterpolatingCharacter()

### In Progress
- [ ] Phase 3: Debug Method::Generate::Constructor->new() returning undef
  - BUILDARGS works correctly
  - bless appears to return undef
  - Need to investigate stash manipulation or bless behavior

### Pending
- [ ] Phase 4: Fix parser bug with `x =>`
- [ ] Phase 5: Test Moo end-to-end

### Next Steps
1. Debug why `bless $hashref, $class` returns undef in Method::Generate::Constructor
2. Check if `delete _getstash(__PACKAGE__)->{new}` causes issues
3. Once constructor works, tackle the `x =>` parser bug

## Related Documents

- `dev/design/cpan_client.md` - jcpan implementation
- `dev/import-perl5/README.md` - Module sync process
- `dev/import-perl5/config.yaml` - Module import configuration
