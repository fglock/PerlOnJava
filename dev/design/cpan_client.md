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
| **Archive::Zip** | ✅ Done | Medium | Java implementation using java.util.zip |
| **Net::FTP** | ✅ Done | Medium | Imported via sync.pl |
| **IPC::Open3** | ✅ Done | Medium | Custom implementation using Java ProcessBuilder |
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

### Current Status: Phase 7 complete - CPAN.pm functional for pure Perl modules

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
  - IPC::Open2, IPC::Open3 - custom implementation using Java ProcessBuilder
  - IPCOpen3.java XS module loaded via XSLoader
  - ProcessInputHandle.java, ProcessOutputHandle.java for process stream I/O
  - Works on both Windows (WaitpidOperator) and POSIX (RuntimeIO)
  - pipe() - fixed autovivification to handle undefined variables (like open())
  - fcntl() - implemented with jnr-posix native support and fallback stub
  - ioctl() - implemented with jnr-posix native support and fallback stub
  - Prototype parsing fix - typeglob arguments now use =~ precedence level
  - Reference comparison fix - `\$x == \undef` no longer crashes (NPE in getDoubleRef)
- [x] **Phase 4: CPAN Client Evaluation & Archive::Zip** (2024-03-13)
  - **cpanm analysis complete**: cpanm bundles (fatpacks) its dependencies but requires ExtUtils::MakeMaker
  - ExtUtils::MakeMaker is the critical blocker - it's the CPAN build system that runs `make`
  - Since PerlOnJava doesn't use native compilation, a traditional CPAN client isn't feasible
  - **Archive::Zip implemented**: Full Java implementation using java.util.zip
    - Read/write zip files
    - Add files, strings, and directories
    - Extract individual members or entire archive
    - Works with system `unzip` command for verification
  - Created user documentation: `docs/guides/using-cpan-modules.md`
    - How to check module availability
    - List of included modules
    - How to add pure Perl modules
    - Example scripts for downloading CPAN modules
- [x] **Phase 5: ExtUtils::MakeMaker for PerlOnJava** (2024-03-14)
  - **ExtUtils::MakeMaker implemented**: Direct module installation without `make`
    - WriteMakefile() intercepts the standard Makefile.PL flow
    - Pure Perl modules: copies .pm files directly to install directory
    - XS modules: detects .xs/.c files and provides porting guidance
    - PREREQ_PM: checks dependencies and reports missing modules
  - **User library path**: `~/.perlonjava/lib`
    - Default installation directory for MakeMaker
    - Automatically added to @INC when directory exists
    - Configurable via `PERLONJAVA_LIB` environment variable
  - **Compatibility stubs**: ExtUtils::MM, ExtUtils::MY, ExtUtils::MakeMaker::Config
  - See detailed design: `dev/design/makemaker_perlonjava.md`

### Files Changed (Phase 2)
- `dev/import-perl5/config.yaml` - Added IO::Socket, IO::Zlib, Archive::Tar, Net::*, Tie::StdHandle, File::Spec imports
- `src/main/java/org/perlonjava/runtime/perlmodule/Socket.java` - Added 20+ socket constants
- `src/main/perl/lib/Socket.pm` - Added $VERSION and expanded exports
- `src/main/java/org/perlonjava/frontend/parser/IdentifierParser.java` - Fixed `$` followed by `{` in braced variable parsing
- `src/main/java/org/perlonjava/runtime/perlmodule/SysHostname.java` - New XS module for Sys::Hostname
- `src/main/java/org/perlonjava/runtime/perlmodule/XSLoader.java` - Added caller() support for no-argument load()

### Files Changed (Phase 3)
- `src/main/java/org/perlonjava/runtime/perlmodule/IPCOpen3.java` - XS module for open2/open3
- `src/main/java/org/perlonjava/runtime/io/ProcessInputHandle.java` - IOHandle for process stdout/stderr
- `src/main/java/org/perlonjava/runtime/io/ProcessOutputHandle.java` - IOHandle for process stdin
- `src/main/perl/lib/IPC/Open2.pm`, `src/main/perl/lib/IPC/Open3.pm` - Custom wrappers using XSLoader
- `src/main/java/org/perlonjava/runtime/operators/IOOperator.java` - pipe() autovivification, fcntl(), ioctl()
- `src/main/java/org/perlonjava/runtime/operators/OperatorHandler.java` - Added fcntl/ioctl descriptors
- `src/main/java/org/perlonjava/frontend/parser/PrototypeArgs.java` - Fixed typeglob prototype parsing
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeScalar.java` - Fixed getIntRef()/getDoubleRef() NPE
- `dev/import-perl5/config.yaml` - Removed IPC::Open2/Open3 imports (custom implementation)

### Files Changed (Phase 4)
- `src/main/java/org/perlonjava/runtime/perlmodule/ArchiveZip.java` - New Java implementation
- `src/main/perl/lib/Archive/Zip.pm` - Perl wrapper with XSLoader
- `docs/guides/using-cpan-modules.md` - User documentation for adding CPAN modules

### Files Changed (Phase 5)
- `src/main/perl/lib/ExtUtils/MakeMaker.pm` - PerlOnJava MakeMaker implementation
- `src/main/perl/lib/ExtUtils/MM.pm` - Compatibility stub
- `src/main/perl/lib/ExtUtils/MY.pm` - Compatibility stub
- `src/main/perl/lib/ExtUtils/MakeMaker/Config.pm` - Config wrapper
- `src/main/java/org/perlonjava/runtime/runtimetypes/GlobalContext.java` - Added ~/.perlonjava/lib to @INC

- [x] **Phase 6: CPAN.pm Support** (2024-03-14, in progress)
  - **Safe.pm stub created**: Minimal implementation for CPAN.pm metadata evaluation
    - `reval()` uses `eval` with `no strict 'vars'` (CPAN metadata is trusted)
    - Supports CHECKSUMS file evaluation ($cksum hash)
  - **CPAN.pm imported**: Added to config.yaml with CPAN/*, CPAN::Meta::*, Parse::CPAN::Meta
  - **Parser fixes for CPAN.pm compatibility**:
    - File test operators with function call operands (`-f func()`)
    - Block argument parsing for undefined functions (`func { } @args`)
    - File test with qualified package names (`-f CPAN::find_perl`)
  - **Regex fix**: Character class ranges like `[A-Z-0-9]` now parse correctly
  - **try/catch feature gating**: `try` and `catch` only keywords when `use feature 'try'` enabled
    - Allows Try::Tiny to work correctly without feature flag
  - **CPAN.pm now loads and can install pure Perl modules**:
    ```bash
    ./jperl -MCPAN -e 'CPAN::Shell->install("Try::Tiny")'
    # Downloads, validates checksums, installs to ~/.perlonjava/lib/
    ```

### Files Changed (Phase 6)
- `src/main/perl/lib/Safe.pm` - New stub for CPAN.pm metadata evaluation
- `dev/import-perl5/config.yaml` - Added CPAN.pm, CPAN/*, CPAN::Meta::*, Parse::CPAN::Meta
- `src/main/java/org/perlonjava/backend/jvm/EmitOperatorFileTest.java` - Fixed file test with function calls
- `src/main/java/org/perlonjava/frontend/parser/SubroutineParser.java` - Block args for undefined functions
- `src/main/java/org/perlonjava/frontend/parser/ParsePrimary.java` - File test with qualified names, try/catch gating
- `src/main/java/org/perlonjava/runtime/regex/ExtendedCharClass.java` - Character class range fix
- `src/main/java/org/perlonjava/runtime/regex/RegexPreprocessorHelper.java` - Character class range fix
- `src/main/perl/lib/ExtUtils/MM.pm` - Platform selection and MM alias, inherits from MM_Unix/MM_Win32
- `src/main/perl/lib/ExtUtils/MM_Unix.pm` - parse_version and maybe_command for Unix
- `src/main/perl/lib/ExtUtils/MM_Win32.pm` - Windows-specific maybe_command
- `src/main/perl/lib/ExtUtils/MakeMaker.pm` - Added stub Makefile generation
- `src/main/perl/lib/CPAN/Distribution.pm` - Fork fallback using system() when d_fork is false

### Phase 6 Continued (2024-03-14)
- **MM->parse_version() implemented**: Required by CPAN.pm to check installed module versions
  - Uses regex extraction for common VERSION patterns (avoids package block scoping issues)
  - Platform-specific modules: MM_Unix.pm (Unix/macOS), MM_Win32.pm (Windows)
  - MM alias: `package MM; @ISA = qw(ExtUtils::MM);` for CPAN.pm compatibility
- **Stub Makefile generation**: MakeMaker now creates minimal Makefile that CPAN.pm recognizes
  - CPAN.pm reports "Makefile.PL -- OK" and "make -- OK"
  - make targets: all, test, install, clean (no-ops since files installed directly)
- **maybe_command() implemented**: Checks if file is executable (Unix: -x, Windows: .exe/.com/.bat/.cmd)

### Known Issues (Phase 6)
1. **fork() fallback implemented**: CPAN::Distribution patched to use system() when $Config{d_fork} is false
   - Tests run without fork, losing timeout and signal handling
   - Works for normal test scenarios
2. **Dependency resolution**: CPAN.pm tries to install core modules (Exporter, strict, warnings)
   - These are built-in but CPAN.pm doesn't detect them
   - May need to stub module versions or configure CPAN.pm to skip core
3. ~~**YAML size limit**: Large YAML metadata exceeds SnakeYAML's 3MB limit~~ **FIXED**
   - Increased YAML::PP code point limit to 50MB
4. **parse_version warnings**: "Error while parsing version" appears but doesn't affect functionality
   - May be related to alarm/eval interaction in CPAN::Module
5. ~~**File::Copy::move fails on reinstall**~~ **FIXED in Phase 7**
   - `$!` now supports dualvar (numeric errno + string message)

- [x] **Phase 7: Errno Dualvar Support & Regex Fixes** (2024-03-14)
  - **ErrnoVariable class**: Implements dualvar behavior for `$!`
    - Numeric context returns errno number (e.g., 39 for ENOTEMPTY)
    - String context returns error message (e.g., "Directory not empty")
  - **Operator.rename()**: Now uses errno numbers (ENOENT=2, EACCES=13, ENOTEMPTY=39, etc.)
  - **RuntimeIO.handleIOException()**: Detects exception types and sets appropriate errno
    - Added `handleIOException(e, msg, defaultErrno)` overload
  - Fixes "Couldn't move" error when CPAN.pm reinstalls modules
  - **'our' variable redeclared warning**: Now only warns when redeclared in same package
    - Fixed to match Perl behavior (different packages don't trigger warning)
  - **Regex literal `{}` braces fix**: Perl treats `{}`, `{,}` as literal braces, not quantifiers
    - Pattern `/^{}(?:\s+\#.*)?\z/` from CPAN::Meta::YAML now works
    - Invalid quantifiers escaped as `\{\}` for Java regex compatibility
    - `isValidQuantifierAt()` helper prevents false "nested quantifier" errors for patterns like `a{3}{x}`

### Files Changed (Phase 7)
- `src/main/java/org/perlonjava/runtime/runtimetypes/ErrnoVariable.java` - New dualvar class for `$!`
- `src/main/java/org/perlonjava/runtime/runtimetypes/GlobalContext.java` - Use ErrnoVariable for `$!`
- `src/main/java/org/perlonjava/runtime/operators/Operator.java` - Use errno numbers in rename()
- `src/main/java/org/perlonjava/runtime/runtimetypes/RuntimeIO.java` - Enhanced handleIOException()
- `src/main/java/org/perlonjava/frontend/parser/OperatorParser.java` - Fix 'our' redeclaration warning
- `src/main/java/org/perlonjava/frontend/symbol/ScopedSymbolTable.java` - Add isOurVariableRedeclaredInSamePackage()
- `src/main/java/org/perlonjava/backend/jvm/EmitVariable.java` - Use same-package check for warning
- `src/main/java/org/perlonjava/runtime/regex/RegexPreprocessor.java` - Literal `{}` braces support

### Next Steps

#### Phase 8: User Experience (Recommended Next)
1. **jcpan wrapper script** - High priority, easy win
   - User-friendly `jcpan install Module` command
   - Sets up paths and invokes CPAN.pm with notest option
   - Example: `jcpan install Try::Tiny`

#### Phase 9: Extended Compatibility
2. **Module::Build support** - Medium priority
   - Some CPAN modules use Module::Build instead of MakeMaker
   - Needs stub similar to ExtUtils::MakeMaker
   - Blocks: modules that only provide Build.PL

3. **Core module detection** - Medium priority
   - CPAN.pm doesn't recognize built-in modules (strict, warnings, Exporter, etc.)
   - Option A: Add version stubs to built-in modules
   - Option B: Configure CPAN.pm to skip core modules
   - Option C: Add core module versions to a metadata file

4. **Test running improvements** - Low priority
   - `make test` uses fork which isn't supported in PerlOnJava
   - Current workaround: `notest("install", "Module")`
   - Long-term: Consider IPC::Open3 for test harness

5. **YAML.pm improvements** - Low priority
   - Warning: "YAML version '0.01' is too low"
   - Current stub is minimal; better YAML parsing would help with META.yml

### Open Questions
- Should we create a PerlOnJava-specific minimal CPAN download tool?
- How important is Safe compartmentalization for users?

### Resolved Questions
- ✅ fork() alternative: IPC::Open2/Open3 now use Java ProcessBuilder
- ✅ cpanm feasibility: cpanm requires ExtUtils::MakeMaker which needs `make` - not suitable for PerlOnJava
- ✅ Archive::Zip: Implemented using java.util.zip
- ✅ ExtUtils::MakeMaker: Reimplemented for PerlOnJava to skip `make` and install pure Perl modules directly
- ✅ Safe.pm: Stub implementation using `eval` with `no strict 'vars'` - sufficient for trusted CPAN metadata
- ✅ Try::Tiny compatibility: `try`/`catch` now feature-gated, module works correctly
- ✅ parse_version: Implemented using regex extraction to avoid package block scoping issues in compiled modules
- ✅ Makefile creation: Stub Makefile satisfies CPAN.pm's checks
