# Net::SMTP Support for PerlOnJava

## Status: COMPLETE — 19/19 test programs pass (110/110 subtests)

**Branch**: `feature/net-telnet-support`
**Date started**: 2026-04-06
**Module**: libnet 3.15 (Net::SMTP is part of the libnet distribution)
**Test command**: `./jcpan -t Net::SMTP`

## Background

Net::SMTP is part of the libnet CPAN distribution, which provides client-side
networking modules for SMTP, FTP, NNTP, POP3, and related protocols. The module
is pure Perl, relies on IO::Socket::INET for connectivity and Net::Cmd for
command/response handling.

## Test Results Summary

### Current State: 17/19 test programs pass, 2 failures

| Test File | Result | Status |
|-----------|--------|--------|
| t/changes.t | skipped (author) | OK |
| t/config.t | ok | PASS |
| t/critic.t | skipped (author) | OK |
| t/datasend.t | 0/54 | **FAIL** — StackOverflowError |
| t/ftp.t | skipped (no config) | OK |
| t/hostname.t | ok | PASS |
| t/netrc.t | 7/20 | **FAIL** — `$+` bug + read-only error |
| t/nntp.t | skipped (no config) | OK |
| t/nntp_ipv6.t | skipped (no fork) | OK |
| t/nntp_ssl.t | skipped (no SSL) | OK |
| t/pod.t | skipped (author) | OK |
| t/pod_coverage.t | skipped (author) | OK |
| t/pop3_ipv6.t | skipped (no fork) | OK |
| t/pop3_ssl.t | skipped (no SSL) | OK |
| t/require.t | ok | PASS |
| t/smtp.t | skipped (no config) | OK |
| t/smtp_ipv6.t | skipped (no fork) | OK |
| t/smtp_ssl.t | skipped (no SSL) | OK |
| t/time.t | ok | PASS |

## Bugs Found

### Bug 1: IO::File::new_tmpfile infinite recursion — FIXED

**Affected tests**: t/datasend.t (0/54 subtests, StackOverflowError)

**Symptom**: `StackOverflowError` with infinite recursion between
`IO::File::new_tmpfile` (line 163) and `Foo::new` (line 32 of datasend.t).

**Root cause**: PerlOnJava's pure-Perl `IO::File::new_tmpfile()` calls
`$class->new` (line 163) to create the filehandle object. In standard Perl 5,
`new_tmpfile` is an XS function in `IO::Handle` that calls C's `tmpfile()`
directly — it never dispatches through Perl-level `new()`. The PerlOnJava
version uses polymorphic dispatch, so when a subclass (like the test's `Foo`)
overrides `new()` to call `new_tmpfile()`, it creates infinite recursion:

```
Foo->new() → Foo->new_tmpfile() → IO::File::new_tmpfile()
  → $class->new()  [where $class="Foo"]
  → Foo->new() → ... StackOverflow
```

**Fix**: Replace `$class->new` with `bless gensym(), $class` in `new_tmpfile()`.
This directly creates a blessed glob (same as what `IO::Handle::new` does
internally) without polymorphic method dispatch.

**File**: `src/main/perl/lib/IO/File.pm`, line 163

### Bug 2: `$+` (LAST_PAREN_MATCH) returns wrong group in alternations — FIXED

**Affected tests**: t/netrc.t (7/20 subtests, wrong lookup + read-only crash)

**Symptom**: `Net::Netrc->lookup('foo')` returns undef because the `.netrc`
parser fails to extract quoted tokens. The parser at `Net/Netrc.pm` line 91
uses:
```perl
(my $tok = $+) =~ s/\\(.)/$1/g;
```
where the regex is: `s/^("((?:[^"]+|\\.)*)"|((?:[^\\\s]+|\\.)*))\s*//`

For input `"foo"`:
- `$2` = `foo` (content inside quotes, participated in match)
- `$3` = `""` (unquoted alternative, did NOT participate)
- Perl 5: `$+` = `foo` (highest-numbered group that participated)
- PerlOnJava: `$+` = `""` (highest-numbered group, regardless)

**Root cause**: `RuntimeRegex.lastCaptureString()` returns
`lastCaptureGroups[length-1]` — the last array element — without checking if
that group actually participated in the match. Non-participating groups have
`null` values in the array (from Java's `Matcher.group()` returning null).
The fix should iterate backwards and return the first non-null entry.

**Secondary symptom**: After lookup fails, `undef->{password}` at line 103
throws "Modification of a read-only value attempted" because the return
value from `lookup()` is a read-only undef that auto-vivification cannot write to.

**File**: `src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java`,
method `lastCaptureString()` (line 1190)

## Implementation Plan

### Phase 1: Fix `$+` variable for alternation groups ✓ COMPLETE

1. Fix `RuntimeRegex.lastCaptureString()` to iterate backwards through
   `lastCaptureGroups` and return the first non-null value
2. Verify with: `./jperl -e '"test" =~ /(a)|(test)|(c)/; print "$+\n"'`
   Should print `test`, not empty string

**File**: `src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java`

### Phase 2: Fix IO::File::new_tmpfile recursion ✓ COMPLETE

1. Change `my $fh = $class->new;` to `my $fh = bless gensym(), $class;`
2. Verify with datasend.t tests

**File**: `src/main/perl/lib/IO/File.pm`

### Phase 3: Verify and finalize ✓ COMPLETE

1. Rebuild: `make dev`
2. Rerun: `./jcpan -t Net::SMTP` — All 19 test programs pass (110/110 subtests)
3. Run full test suite: `make` — All unit tests pass
4. Commit and create PR

## Test Verification

```bash
# Build
make

# CPAN tests
./jcpan -t Net::SMTP

# Verify $+ fix
./jperl -e '"test" =~ /(a)|(test)|(c)/; print "got: |$+|\n"'
# Expected: got: |test|

# Verify new_tmpfile fix
./jperl -e '
package Foo;
use IO::File;
our @ISA = qw(IO::File);
sub new { my $fh = shift->new_tmpfile; $fh }
my $f = Foo->new;
print defined($f) ? "ok\n" : "not ok\n";
'
# Expected: ok
```

## Progress Tracking

### Current Status: COMPLETE

### Completed Phases
- [x] Phase 1: Fix `$+` for alternation groups (2026-04-06)
  - Fixed `lastCaptureString()` to iterate backwards and return first non-null group
  - File changed: `RuntimeRegex.java`
- [x] Phase 2: Fix IO::File::new_tmpfile recursion (2026-04-06)
  - Replaced `$class->new` with `bless gensym(), $class`
  - File changed: `IO/File.pm`
- [x] Phase 3: Verify and finalize (2026-04-06)
  - All 19 test programs pass (110/110 subtests)
  - All unit tests pass (no regressions)

### Files Modified
- `src/main/java/org/perlonjava/runtime/regex/RuntimeRegex.java` — `lastCaptureString()` method
- `src/main/perl/lib/IO/File.pm` — `new_tmpfile()` method

## Related Documents
- `dev/modules/net_telnet.md` — Net::Telnet (same branch, same libnet family)
- `dev/modules/lwp_useragent.md` — LWP uses socket I/O
