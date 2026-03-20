# CPAN Client Support for PerlOnJava

## Overview

This document tracks CPAN client support for PerlOnJava. The `jcpan` command provides full CPAN functionality for pure Perl modules.

## Current Status (2026-03-20)

**Working:**
- `jcpan install Module::Name` - Install pure Perl modules from CPAN
- `jcpan -f install Module::Name` - Force install (skip tests)
- `jcpan -t Module::Name` - Test a module
- Interactive CPAN shell via `jcpan`
- **DateTime** - Core functionality working (new, datetime, add, subtract, formatting)

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

DateTime installation via jcpan completes but the module had issues loading due to its complex dependency chain involving namespace::autoclean.

### Current Status (2026-03-20)

**DateTime core functionality is working:**
```bash
./jperl -MDateTime -e '
  my $dt = DateTime->new(year => 2024, month => 3, day => 15, hour => 10);
  print $dt->datetime, "\n";  # 2024-03-15T10:00:00
  $dt->add(days => 5);
  print $dt->datetime, "\n";  # 2024-03-20T10:00:00
'
```

**Timezone support has remaining issues:**
```bash
# Fails because namespace::autoclean cleans imported Try::Tiny functions
./jperl -MDateTime -e '
  my $dt = DateTime->new(year => 2024, time_zone => "America/New_York");
'
# Error: Undefined subroutine &DateTime::TimeZone::catch
```

### Issues Fixed in Phase 11

| Issue | Fix | Commit |
|-------|-----|--------|
| `${ $stash{NAME} }` dereference | Fixed symbol table access | |
| GLOBREFERENCE scalar dereference | `$$globref` now returns the glob itself | |
| map/grep @_ access | Blocks now access outer subroutine's @_ | 67da75215 |
| B::Hooks::EndOfScope NPE | Null check for fileName in beginFileLoad/endFileLoad | 5dc05ca6d |
| **Sub::Util::subname for glob-installed code** | When code is installed via `*pkg::name = \&code`, the RuntimeCode's packageName/subName are updated | 8bc1e451e |

### Root Cause of namespace::autoclean Issue (PARTIALLY FIXED)

**Fixed:** Methods installed via direct glob assignment (`*pkg::name = \&code`) now have correct subname.

**Still broken:** Imported functions via Exporter. When `use Try::Tiny` imports `catch` into DateTime::TimeZone:
- `Sub::Util::subname(\&DateTime::TimeZone::catch)` returns `Try::Tiny::catch`
- namespace::autoclean sees package mismatch and removes it
- This matches Perl behavior for subname, but Perl's namespace::autoclean handles this case differently

**Why we can't easily fix Exporter imports:**
- Exporter creates aliases - `DateTime::TimeZone::catch` points to same RuntimeCode as `Try::Tiny::catch`
- Modifying packageName/subName would affect ALL importers
- Cloning RuntimeCode would break aliasing semantics

### Remaining Issues

1. **namespace::autoclean + Exporter imports** - Imported functions are cleaned
   - Affects: DateTime::TimeZone (Try::Tiny), and likely other modules
   - Workaround: Exclude imported functions in `-except` list (requires patching)
   
2. **XS fallback** - DateTime uses pure Perl mode; XS bridge not yet implemented
   - Low priority since PP mode works

### Next Steps

1. **Investigate how real Perl handles this** - Compare namespace::autoclean behavior
2. **Consider namespace::autoclean stub** - Could skip cleanup entirely or use different logic
3. **Document workarounds** - Manual patches to exclude imported functions

---

## Related Documents

- `dev/design/xsloader.md` - XSLoader/Java integration
- `dev/design/makemaker_perlonjava.md` - ExtUtils::MakeMaker implementation
- `.cognition/skills/port-cpan-module/` - Skill for porting CPAN modules
- `docs/guides/using-cpan-modules.md` - User documentation
