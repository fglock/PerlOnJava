# CPAN Client Support for PerlOnJava

## Overview

This document analyzes what's needed to run CPAN.pm (or alternatives) on PerlOnJava.

## Current Status

CPAN.pm has deep dependencies that make it challenging to port. The main blocker is `Safe`/`Opcode` which requires access to Perl's internal opcode system.

---

## CPAN.pm Dependency Analysis

### Available (Already Working)

| Module | Status |
|--------|--------|
| File::Spec, File::Basename, File::Copy, File::Find, File::Path, File::Temp | ✅ |
| Text::ParseWords, Text::Wrap | ✅ |
| Config, Carp, Cwd, Exporter, Fcntl | ✅ |
| FileHandle, IO::File, IO::Handle | ✅ |
| HTTP::Tiny, Compress::Zlib | ✅ |
| Digest::MD5, Digest::SHA, MIME::Base64 | ✅ |
| YAML, JSON, Term::ReadLine | ✅ |

### Critical Missing Modules

| Module | Status | Complexity | Notes |
|--------|--------|------------|-------|
| **Safe** | ❌ Missing | High | Sandbox/compartment module - requires Opcode |
| **Opcode** | ❌ Missing | Very High | Core opcodes restriction - deeply tied to Perl internals |
| **DirHandle** | ❌ Missing | Low | OO interface to opendir/readdir - pure Perl possible |
| **Sys::Hostname** | ❌ Missing | Low | `hostname()` function - easy Java implementation |
| **ExtUtils::MakeMaker** | ❌ Missing | Very High | Build system - huge module with many dependencies |
| **LWP::UserAgent** | ❌ Missing | Medium | Web client (HTTP::Tiny exists as alternative) |
| **Archive::Tar** | ❌ Missing | Medium | Tar extraction - Java has built-in support |
| **Archive::Zip** | ❌ Missing | Medium | Zip handling - Java has built-in support |
| **Net::FTP** | ❌ Missing | Medium | FTP client - Java has FTP support |
| **IPC::Open3** | ❌ Missing | Medium | Process I/O - needs Java ProcessBuilder |
| **IO::Socket** | ❌ Missing | Medium | Socket I/O - Java has native support |
| **Dumpvalue** | ❌ Missing | Low | Debug output - pure Perl possible |

### Built-in Functions Missing

| Function | Status | Notes |
|----------|--------|-------|
| `flock()` | ❌ Not implemented | File locking - Java has FileLock API |

---

## Import Strategy via sync.pl

The `dev/import-perl5/sync.pl` script can import pure Perl modules from the perl5 source tree.

### Quick Wins - Add to config.yaml

These modules can be imported directly:

```yaml
# DirHandle - OO directory handle interface
- source: perl5/lib/DirHandle.pm
  target: src/main/perl/lib/DirHandle.pm

# Dumpvalue - Debug dump utility
- source: perl5/dist/Dumpvalue/lib/Dumpvalue.pm
  target: src/main/perl/lib/Dumpvalue.pm

# Sys::Hostname - Get system hostname
- source: perl5/ext/Sys-Hostname/Hostname.pm
  target: src/main/perl/lib/Sys/Hostname.pm

# IPC::Open3 - Open process with 3 filehandles
- source: perl5/ext/IPC-Open3/lib/IPC/Open3.pm
  target: src/main/perl/lib/IPC/Open3.pm

# Archive::Tar (if IO::Zlib is available)
- source: perl5/cpan/Archive-Tar/lib/Archive/Tar.pm
  target: src/main/perl/lib/Archive/Tar.pm
- source: perl5/cpan/Archive-Tar/lib/Archive/Tar
  target: src/main/perl/lib/Archive/Tar
  type: directory

# Net::FTP and libnet modules
- source: perl5/cpan/libnet/lib/Net
  target: src/main/perl/lib/Net
  type: directory
```

### Modules Requiring Java Implementation

| Module | Java Implementation Needed |
|--------|---------------------------|
| **flock()** | `java.nio.channels.FileLock` in RuntimeIO.java |
| **IO::Socket** | Wrap `java.net.Socket` / `java.net.ServerSocket` |
| **Sys::Hostname** (XS part) | `java.net.InetAddress.getLocalHost().getHostName()` |

---

## The Safe/Opcode Blocker

**Safe.pm** is used by CPAN.pm to safely evaluate CPAN metadata (like `META.yml` code). It depends on **Opcode.pm** which:

1. Uses XSLoader (has C code)
2. Manipulates Perl's internal opcode tree
3. Restricts which operations can run in a compartment

### Why This Is Hard

Opcode works by:
- Enumerating all Perl opcodes (300+)
- Creating bitmasks to allow/deny specific operations
- Hooking into Perl's internal compilation

PerlOnJava compiles to JVM bytecode, not Perl opcodes. Implementing Opcode would require:
- Mapping Perl opcodes to JVM operations
- Implementing compartmentalization at the JVM level
- Possibly using Java SecurityManager (deprecated in newer Java)

**Verdict**: Opcode/Safe would require significant architectural work.

---

## Alternative Approaches

### Option 1: Use cpanm (App::cpanminus)

cpanm is a lighter CPAN client. Need to analyze its dependencies.

```bash
# Check cpanm dependencies
curl -s https://cpanmin.us | head -200
```

### Option 2: Minimal CPAN Client

Create a simple CPAN client using modules that already work:

```perl
# Pseudo-code for minimal CPAN client
use HTTP::Tiny;
use Archive::Tar;  # needs import
use File::Temp;

sub install_module {
    my ($module) = @_;
    
    # 1. Query MetaCPAN API
    my $http = HTTP::Tiny->new;
    my $resp = $http->get("https://fastapi.metacpan.org/v1/download_url/$module");
    
    # 2. Download tarball
    my $tarball = download($resp->{download_url});
    
    # 3. Extract
    Archive::Tar->extract_archive($tarball);
    
    # 4. Run Makefile.PL or Build.PL (this is the hard part)
}
```

### Option 3: Pre-bundle Modules

Instead of a CPAN client, import pure-Perl modules directly:

1. Identify commonly needed CPAN modules
2. Add them to `dev/import-perl5/config.yaml`
3. Run `perl dev/import-perl5/sync.pl`

This is already working for many modules (Pod::*, Test::*, Getopt::Long, etc.)

---

## Implementation Priority

### Phase 1: Low-hanging fruit (Easy)

1. **DirHandle** - Add to config.yaml, pure Perl
2. **Dumpvalue** - Add to config.yaml, pure Perl  
3. **Sys::Hostname** - Import + Java fallback
4. **flock()** - Implement in Java using FileLock

### Phase 2: Archive/Network (Medium)

5. **Archive::Tar** - Import from perl5 tree (needs IO::Zlib check)
6. **Archive::Zip** - Java implementation using `java.util.zip`
7. **IO::Socket** - Java implementation wrapping sockets
8. **Net::FTP** - Import if IO::Socket works

### Phase 3: Process Control (Medium)

9. **IPC::Open3** - Import + verify pipe support
10. **IPC::Cmd** - Import if Open3 works

### Phase 4: Consider Alternatives

11. Evaluate cpanm dependencies
12. Consider minimal custom CPAN client
13. Document "how to add a CPAN module" for users

---

## Testing Commands

```bash
# Test module availability
./jperl -e 'use DirHandle; print "OK\n"'
./jperl -e 'use Sys::Hostname; print hostname(), "\n"'
./jperl -e 'use Archive::Tar; print "OK\n"'

# Test flock (currently fails)
./jperl -e 'use Fcntl qw(:flock); open my $fh, "<", $0; flock($fh, LOCK_SH); print "OK\n"'
```

---

## Related Documents

- `dev/design/xsloader.md` - How XSLoader/Java integration works
- `dev/design/http_server.md` - HTTP capabilities
- `.cognition/skills/port-cpan-module/` - Skill for porting CPAN modules

---

## Progress Tracking

### Current Status: Phase 1 complete

### Completed
- [x] Analyze CPAN.pm dependencies (2024-03-13)
- [x] Identify modules available in perl5 tree
- [x] Document sync.pl import strategy
- [x] Identify Safe/Opcode blocker
- [x] **Phase 1: Low-hanging fruit** (2024-03-13)
  - DirHandle - imported via sync.pl, fixed Symbol::gensym() to return GLOB reference
  - Dumpvalue - imported via sync.pl, fixed parser bug with `%package:: and` syntax
  - Sys::Hostname - imported via sync.pl, implemented syscall() operator
  - flock() - implemented in CustomFileChannel.java using java.nio.channels.FileLock
  - syscall() - implemented in SyscallOperator.java with gethostname support

### Files Changed (Phase 1)
- `dev/import-perl5/config.yaml` - Added DirHandle, Dumpvalue, Sys::Hostname imports
- `src/main/java/org/perlonjava/runtime/io/IOHandle.java` - Added flock() interface method
- `src/main/java/org/perlonjava/runtime/io/CustomFileChannel.java` - Implemented flock()
- `src/main/java/org/perlonjava/runtime/operators/IOOperator.java` - Added flock() operator
- `src/main/java/org/perlonjava/runtime/operators/SyscallOperator.java` - New file for syscall()
- `src/main/java/org/perlonjava/runtime/operators/OperatorHandler.java` - Registered flock, syscall
- `src/main/java/org/perlonjava/backend/bytecode/Opcodes.java` - Added FLOCK opcode
- `src/main/java/org/perlonjava/backend/bytecode/CompileOperator.java` - Wired flock
- `src/main/java/org/perlonjava/backend/bytecode/Disassemble.java` - Added flock disassembly
- `src/main/java/org/perlonjava/backend/bytecode/SlowOpcodeHandler.java` - Implemented syscall handler
- `src/main/java/org/perlonjava/runtime/perlmodule/Symbol.java` - Fixed gensym() to return reference
- `src/main/java/org/perlonjava/frontend/parser/IdentifierParser.java` - Fixed %pkg:: and parsing

### Next Steps
1. Phase 2: Archive/Network modules (Archive::Tar, IO::Socket)
2. Phase 3: Process control (IPC::Open3)
3. Evaluate cpanm as alternative to CPAN.pm

### Open Questions
- Is cpanm lighter on dependencies than CPAN.pm?
- Should we create a PerlOnJava-specific minimal CPAN client?
- How important is Safe compartmentalization for users?
