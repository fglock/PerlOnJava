# CPAN Client Support for PerlOnJava

## Overview

This document tracks CPAN client support for PerlOnJava. The `jcpan` command provides full CPAN functionality for pure Perl modules.

## Current Status (2026-03-19)

**Working:**
- `jcpan install Module::Name` - Install pure Perl modules from CPAN
- `jcpan -f install Module::Name` - Force install (skip tests)
- `jcpan -t Module::Name` - Test a module
- Interactive CPAN shell via `jcpan`

**Known Limitations:**
- XS modules require manual porting (see `.cognition/skills/port-cpan-module/`)
- Module::Build-only modules need Module::Build installed separately
- Tests that heavily use fork may fail or skip
- Safe.pm compartment restrictions are not enforced (uses trusted eval)

---

## Module Availability

### Core Modules (Built-in)

| Category | Modules |
|----------|---------|
| File I/O | File::Spec, File::Basename, File::Copy, File::Find, File::Path, File::Temp |
| Text | Text::ParseWords, Text::Wrap |
| Core | Config, Carp, Cwd, Exporter, Fcntl |
| I/O | FileHandle, IO::File, IO::Handle, IO::Socket |
| Network | HTTP::Tiny, Net::FTP |
| Archive | Archive::Tar, Archive::Zip, Compress::Zlib |
| Crypto | Digest::MD5, Digest::SHA, MIME::Base64 |
| Data | YAML, JSON |
| Process | IPC::Open2, IPC::Open3 |

### Modules Requiring Stubs

| Module | Implementation | Notes |
|--------|----------------|-------|
| Safe | Stub using `eval` | CPAN metadata is trusted |
| ExtUtils::MakeMaker | Custom | Installs directly, no `make` needed |
| Module::Build::Base | Stub | Disables fork pipes |

### Not Implemented

| Module | Reason |
|--------|--------|
| Opcode | Requires Perl opcode internals |
| LWP::UserAgent | Use HTTP::Tiny instead |

---

## The Safe/Opcode Limitation

**Safe.pm** is used by CPAN.pm to evaluate metadata. It depends on **Opcode.pm** which manipulates Perl's internal opcode tree. Since PerlOnJava compiles to JVM bytecode (not Perl opcodes), implementing Opcode would require significant architectural work.

**Current solution:** Safe.pm stub uses `eval` with `no strict 'vars'`. CPAN metadata is trusted, so this is sufficient for normal use.

---

## Completed Phases (Summary)

| Phase | Description | Key Deliverables |
|-------|-------------|------------------|
| 1 | Low-hanging fruit | DirHandle, Dumpvalue, Sys::Hostname, flock() |
| 2 | Archive/Network | IO::Socket, Archive::Tar, Net::FTP |
| 3 | Process Control | IPC::Open2, IPC::Open3 via Java ProcessBuilder |
| 4 | Archive::Zip | Java implementation using java.util.zip |
| 5 | ExtUtils::MakeMaker | Direct installation without `make` |
| 6 | CPAN.pm Support | Safe.pm stub, parser fixes, CPAN shell working |
| 7 | Errno & Regex | `$!` dualvar, literal `{}` braces in regex |
| 8 | User Experience | `jcpan` wrapper script |
| 9 | Polish | YAML version update, Module::Build partial support |

---

## Phase 11: DateTime Support (Active)

### Problem Statement

DateTime installation via jcpan completes but the module has issues loading due to its complex dependency chain involving namespace::autoclean.

### Current Status

**Working in pure Perl mode:**
```bash
PERL_DATETIME_PP=1 ./jperl -MDateTime -e '
  my $dt = DateTime->new(year => 2024, month => 3, day => 15);
  print $dt->ymd;  # 2024-03-15
'
```

**Requires manual patch:** DateTime.pm must exclude PP methods from namespace::autoclean cleanup.

### Issues Fixed in Phase 11

| Issue | Fix |
|-------|-----|
| `${ $stash{NAME} }` dereference | Fixed symbol table access |
| GLOBREFERENCE scalar dereference | `$$globref` now returns the glob itself |
| map/grep @_ access | Blocks now access outer subroutine's @_ |
| B::CV::GV introspection | Uses Sub::Util::subname for actual names |
| Scalar::Util::blessed | Returns undef for unblessed refs |
| B::Hooks::EndOfScope | Rewritten for file-level callbacks |

### Remaining Issues

1. **namespace::autoclean cleans installed methods** - Methods installed via glob assignment from DateTime::PP are cleaned because B module reports original package
2. **DateTime::Locale methods** - Auto-generated locale methods are cleaned
3. **XS fallback** - Forced PP mode required; XS bridge not yet implemented

### Next Steps for DateTime

#### Step 1: Investigate namespace::autoclean Behavior Difference

**Question:** Why does the original DateTime code work with system Perl but not PerlOnJava?

The glob assignment `*{ 'DateTime::' . $sub } = __PACKAGE__->can($sub)` in DateTime::PP installs methods that namespace::autoclean then cleans up. In system Perl, these methods are preserved.

**Investigation needed:**
1. Compare B module behavior between system Perl and PerlOnJava
2. Check what `Sub::Util::subname(\&DateTime::_ymd2rd)` returns in system Perl
3. If system Perl returns the *installed* name, update RuntimeCode to track installation location

**Test commands:**
```bash
# System Perl
perl -MDateTime -MB -MSub::Util=subname -e '
  print "subname: ", subname(\&DateTime::_ymd2rd), "\n";
  my $cv = B::svref_2object(\&DateTime::_ymd2rd);
  print "B stash: ", $cv->GV->STASH->NAME, "\n";
'

# PerlOnJava
PERL_DATETIME_PP=1 ./jperl -MDateTime -MB -MSub::Util=subname -e '
  print "subname: ", subname(\&DateTime::_ymd2rd), "\n";
  my $cv = B::svref_2object(\&DateTime::_ymd2rd);
  print "B stash: ", $cv->GV->STASH->NAME, "\n";
'
```

#### Step 2: Enable XS Fallback to DateTime.java

**Goal:** When DateTime.pm tries `XSLoader::load('DateTime')`, load our Java implementation.

**Implementation:**
1. Create DateTime XS bridge methods in `DateTime.java` (_ymd2rd, _rd2ymd, etc.)
2. Register as XS module in XSLoader
3. Handle version compatibility

**Test plan:**
```bash
# Should use Java XS, not PP
./jperl -MDateTime -e 'print "IsPurePerl: $DateTime::IsPurePerl\n"'
# Expected: IsPurePerl: 0 (or undefined)
```

**Files to modify:**
- `src/main/java/org/perlonjava/runtime/perlmodule/DateTime.java`
- `src/main/java/org/perlonjava/runtime/perlmodule/XSLoader.java`

---

## Related Documents

- `dev/design/xsloader.md` - XSLoader/Java integration
- `dev/design/makemaker_perlonjava.md` - ExtUtils::MakeMaker implementation
- `.cognition/skills/port-cpan-module/` - Skill for porting CPAN modules
- `docs/guides/using-cpan-modules.md` - User documentation
