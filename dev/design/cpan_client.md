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
| **DirHandle** | ✅ Done | Low | OO interface to opendir/readdir - imported via sync.pl |
| **Sys::Hostname** | ✅ Done | Low | `hostname()` function - SysHostname.java XS module |
| **ExtUtils::MakeMaker** | ❌ Missing | Very High | Build system - huge module with many dependencies |
| **LWP::UserAgent** | ❌ Missing | Medium | Web client (HTTP::Tiny exists as alternative) |
| **Archive::Tar** | ✅ Done | Medium | Imported via sync.pl |
| **Archive::Zip** | ❌ Missing | Medium | Zip handling - Java has built-in support |
| **Net::FTP** | ✅ Done | Medium | Imported via sync.pl |
| **IPC::Open3** | ✅ Imported | Medium | Process I/O - imported but fork() not available on JVM |
| **IO::Socket** | ✅ Done | Medium | Imported via sync.pl |
| **Dumpvalue** | ✅ Done | Low | Imported via sync.pl |

### Built-in Functions Missing

| Function | Status | Notes |
|----------|--------|-------|
| `flock()` | ✅ Implemented | File locking - using java.nio.channels.FileLock |

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

### Current Status: Phase 3 complete

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
- [x] **Phase 2: Archive/Network modules** (2024-03-13)
  - IO::Socket, IO::Socket::INET, IO::Socket::UNIX - imported via sync.pl
  - IO::Zlib - imported via sync.pl
  - Archive::Tar - imported via sync.pl, patched GZIP_MAGIC_NUM regex (octal to hex)
  - Net::FTP, Net::Cmd, Net::* - imported via sync.pl
  - Tie::StdHandle - added for IO::Zlib dependency
  - File::Spec platform modules - added for Archive::Tar dependency
  - Socket.pm - added $VERSION and additional constants (INADDR_*, IPPROTO_*, SHUT_*, etc.)
  - Parser fix: `@{${...}}` nested dereference now works in push/unshift
  - SysHostname.java XS module - provides ghname() via InetAddress.getLocalHost()
  - XSLoader caller() support - load() now uses caller() when no argument provided
- [x] **Phase 3: Process Control** (2024-03-13)
  - IPC::Open2, IPC::Open3 - imported via sync.pl
  - pipe() - fixed autovivification to handle undefined variables (like open())
  - fcntl() - implemented with jnr-posix native support and fallback stub
  - ioctl() - implemented with jnr-posix native support and fallback stub
  - Prototype parsing fix - typeglob arguments now use =~ precedence level
  - Reference comparison fix - `\$x == \undef` no longer crashes (NPE in getDoubleRef)
  - **Note**: IPC::Open3 is limited by JVM's lack of fork() support

### Files Changed (Phase 2)
- `dev/import-perl5/config.yaml` - Added IO::Socket, IO::Zlib, Archive::Tar, Net::*, Tie::StdHandle, File::Spec imports
- `src/main/java/org/perlonjava/runtime/perlmodule/Socket.java` - Added 20+ socket constants
- `src/main/perl/lib/Socket.pm` - Added $VERSION and expanded exports
- `src/main/java/org/perlonjava/frontend/parser/IdentifierParser.java` - Fixed `$` followed by `{` in braced variable parsing
- `src/main/java/org/perlonjava/runtime/perlmodule/SysHostname.java` - New XS module for Sys::Hostname
- `src/main/java/org/perlonjava/runtime/perlmodule/XSLoader.java` - Added caller() support for no-argument load()

### Files Changed (Phase 3)
- `dev/import-perl5/config.yaml` - Added IPC::Open2, IPC::Open3 imports
- `src/main/perl/lib/IPC/Open2.pm`, `src/main/perl/lib/IPC/Open3.pm` - Imported from perl5 tree
- `src/main/java/org/perlonjava/runtime/operators/IOOperator.java` - pipe() autovivification, fcntl(), ioctl()
- `src/main/java/org/perlonjava/runtime/operators/OperatorHandler.java` - Added fcntl/ioctl descriptors
- `src/main/java/org/perlonjava/frontend/parser/PrototypeArgs.java` - Fixed typeglob prototype parsing
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalar.java` - Fixed getIntRef()/getDoubleRef() NPE

### Next Steps
1. Phase 4: Evaluate cpanm as alternative to CPAN.pm
2. Consider Archive::Zip implementation using java.util.zip
3. Document "how to add a CPAN module" for users

### Open Questions
- Is cpanm lighter on dependencies than CPAN.pm?
- Should we create a PerlOnJava-specific minimal CPAN client?
- How important is Safe compartmentalization for users?
- Can we implement a Java-based alternative to fork() for process spawning?
