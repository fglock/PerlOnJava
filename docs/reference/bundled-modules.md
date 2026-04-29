# Bundled Modules Reference

PerlOnJava ships with 150+ modules built into the JAR — no installation needed.
Additional pure-Perl modules can be installed from CPAN with
[jcpan](../guides/using-cpan-modules.md).

This page lists every bundled module, grouped by category, and documents
modules that have **external requirements** or **special instructions**.

---

## Modules with External Requirements

Some bundled modules need external software to be installed separately.

### DBI — Database Access

`DBI` is bundled with a JDBC backend.  **SQLite works out of the box** (the
driver is included in the JAR).  Other databases require adding a JDBC driver.

```perl
use DBI;
my $dbh = DBI->connect("dbi:SQLite:dbname=:memory:", "", "");
```

For MySQL, PostgreSQL, Oracle, BigQuery, and other databases, see the full
guide: **[Database Access Guide](../guides/database-access.md)**.

| Database   | Driver setup |
|------------|-------------|
| SQLite     | Built-in — nothing to install |
| MySQL      | `./jperl Configure.pl --search mysql-connector-java` |
| PostgreSQL | `./jperl Configure.pl --search postgresql` |
| Oracle     | `./jperl Configure.pl --search ojdbc` |

### Image::Magick — Image Processing

`Image::Magick` is a pure-Perl CLI wrapper that delegates to ImageMagick
command-line tools.  It provides the same API as the CPAN XS module
(Read, Write, Resize, Crop, Annotate, etc.) without requiring native
PerlMagick bindings.

**Requires ImageMagick CLI tools to be installed and in PATH.**

```bash
# macOS
brew install imagemagick

# Ubuntu / Debian
sudo apt install imagemagick

# Windows
choco install imagemagick
```

ImageMagick 7 (`magick` command) is preferred.  ImageMagick 6
(`convert`/`identify`) is also supported on Linux and macOS.

```perl
use Image::Magick;
my $img = Image::Magick->new;
$img->Set(size => '200x200');
$img->Read('xc:white');
$img->Resize(geometry => '100x100');
$img->Write('output.png');
```

See the design document for implementation details:
[dev/modules/image_magick.md](../../dev/modules/image_magick.md).

---

## Moose / Class::MOP

PerlOnJava bundles upstream **Moose 2.4000** in the JAR — `use Moose;`
works out of the box, no extra install needed.

```perl
use Moose;

has name => (is => 'ro', isa => 'Str', required => 1);
has age  => (is => 'rw', isa => 'Int', default  => 0);

__PACKAGE__->meta->make_immutable;
```

### Status

The bundled stack passes the bulk of the upstream test suites:

| Suite | Files | Asserts | Result |
|-------|-------|--------:|--------|
| **Moose 2.4000 own tests** | ≥396/478 | ≥13413/13550 (~99%) | Mostly green |
| **DBIx::Class 0.082843** (depends on `Moo`; both installed via `jcpan`) | 314/314 | 13858/13858 | **PASS** |

Run the full Moose test suite locally with:

```bash
./jcpan -t Moose
```

DBIx::Class itself depends on `Moo`, not Moose. Both are pure-Perl
modules — `jcpan` will install them from CPAN before running the test
suite:

```bash
./jcpan -t DBIx::Class
```

### Known limitations

The remaining ~80 failing test files cluster around features that are
deliberately out of scope or genuinely unimplemented:

- **`threads` / `fork`** — PerlOnJava has neither.  The handful of
  tests that exercise `share`, `lock`, or fork-based DEMOLISH timing
  cannot pass and are expected to fail.
- **Numeric warning messages** (`Argument "x" isn't numeric in addition`)
  — PerlOnJava does not emit these specific warning categories yet.
- **Stack-trace shape** — frames inside generated method modifiers
  may stringify as `__ANON__` rather than `Pkg::method`.
- **`Moose::Exception` attributes named `INC`** — attribute name
  collision with the `@INC` global.
- **Anonymous metaclass GC timing** — depends on the JVM's reachability
  walker scheduler; deterministic only for hierarchies in the
  Class::MOP / Moose / Moo class set.
- **Native trait Hash `coerce` + `delete`** — corner cases in the
  generated coercion path.

For the up-to-date list and design rationale, see
[dev/modules/moose_support.md](../../dev/modules/moose_support.md).

### Performance

The bundled Moose stack runs the DBIx::Class test suite in
~29 minutes on this hardware (single-job mode).  No formal comparison
to CPAN-Perl + XS is published yet.

---

## Module Categories

### Core / Pragmas

These are loaded automatically or via `use`:

| Module | Implementation | Notes |
|--------|---------------|-------|
| `strict` | Java | |
| `warnings` | Java | |
| `utf8` | Java | |
| `feature` | Java | |
| `integer` | Java | |
| `bytes` | Java | |
| `lib` | Java | |
| `base` | Java | |
| `parent` | Java | |
| `vars` | Java | |
| `subs` | Java | |
| `attributes` | Java | |
| `overload` | Java + Perl | |
| `overloading` | Java | |
| `re` | Java | |
| `mro` | Java | |
| `builtin` | Java | |
| `version` | Java + Perl | |
| `Exporter` | Perl | |
| `AutoLoader` | Perl | |
| `English` | Perl | |
| `Env` | Perl | |
| `Fatal` | Perl | |
| `Config` | Perl | |
| `Errno` | Perl | |
| `Fcntl` | Perl | |
| `B` | Perl | Partial — enough for B::Deparse |
| `UNIVERSAL` | Java | `isa`, `can`, `DOES`, `VERSION` |

### Data Processing

| Module | Implementation | Notes |
|--------|---------------|-------|
| `Data::Dumper` | Java + Perl | |
| `JSON` / `JSON::PP` | Perl | `JSON` delegates to the bundled pure-Perl `JSON::PP` |
| `YAML::PP` | Java + Perl | |
| `TOML` | Java | |
| `Text::CSV` | Java | |
| `Storable` | Java + Perl | `freeze`, `thaw`, `dclone`; `retrieve` reads native Perl binary `pst0` files (system-perl-compatible); `store`/`freeze` still emit a custom format and are not yet readable by system perl — see `dev/modules/storable_binary_format.md` |
| `Clone` | Java + Perl | Deep copy |
| `Scalar::Util` | Java | `blessed`, `reftype`, `weaken`, `dualvar`, etc. |
| `List::Util` | Java | `reduce`, `first`, `min`, `max`, `sum`, `mesh`/`zip`, etc. |
| `Hash::Util` | Java | `lock_keys`, `lock_hash`, etc. |

### File & I/O

| Module | Implementation | Notes |
|--------|---------------|-------|
| `File::Spec` | Java + Perl | Platform-specific variants included |
| `File::Basename` | Perl | |
| `File::Copy` | Perl | |
| `File::Find` | Perl | |
| `File::Glob` | Perl | |
| `File::Path` | Perl | `make_path`, `remove_tree` |
| `File::Temp` | Java + Perl | |
| `File::stat` | Perl | |
| `File::Compare` | Perl | |
| `Cwd` | Java | |
| `IO::File` | Perl | |
| `IO::Handle` | Java + Perl | |
| `IO::Dir` | Perl | |
| `IO::Select` | Perl | |
| `IO::Seekable` | Perl | |
| `IO::Zlib` | Perl | |
| `FileHandle` | Perl | |
| `DirHandle` | Perl | |
| `SelectSaver` | Perl | |
| `PerlIO::encoding` | Perl | |

### Network & Web

| Module | Implementation | Notes |
|--------|---------------|-------|
| `HTTP::Tiny` | Java + Perl | Java `HttpClient` backend |
| `HTTP::Date` | Perl | |
| `HTTP::CookieJar` | Perl | |
| `Socket` | Java + Perl | |
| `IO::Socket::INET` | Perl | |
| `IO::Socket::IP` | Perl | |
| `IO::Socket::UNIX` | Perl | |
| `IO::Socket::SSL` | Java + Perl | Uses JVM TLS (JSSE) — no OpenSSL needed |
| `Net::SSLeay` | Java + Perl | Uses JVM TLS (JSSE) — no OpenSSL needed |
| `Net::FTP` | Perl | |
| `Net::SMTP` | Perl | |
| `Net::POP3` | Perl | |
| `Net::NNTP` | Perl | |
| `Net::Ping` | Perl | |
| `Net::Cmd` | Perl | |
| `URI::Escape` | Perl | |

### Database

| Module | Implementation | Notes |
|--------|---------------|-------|
| `DBI` | Java + Perl | JDBC backend; see [Database Access](../guides/database-access.md) |
| `DBD::SQLite` | Perl | Built-in — no driver setup needed |
| `DBD::Mem` | Perl | In-memory tables |

### Cryptography & Encoding

| Module | Implementation | Notes |
|--------|---------------|-------|
| `Digest::MD5` | Java | `java.security.MessageDigest` |
| `Digest::SHA` | Java | `java.security.MessageDigest` |
| `Digest` | Perl | |
| `MIME::Base64` | Java | |
| `MIME::QuotedPrint` | Java | |
| `Encode` | Java + Perl | |
| `Unicode::Normalize` | Java | |
| `Unicode::UCD` | Java | |

### Archives & Compression

| Module | Implementation | Notes |
|--------|---------------|-------|
| `Archive::Tar` | Perl | |
| `Archive::Zip` | Java + Perl | Uses `java.util.zip` |
| `Compress::Raw::Zlib` | Java | Uses `java.util.zip` |
| `Compress::Zlib` | Java + Perl | |
| `IO::Zlib` | Perl | |

### XML & HTML

| Module | Implementation | Notes |
|--------|---------------|-------|
| `XML::Parser` | Perl | |
| `XML::Parser::Expat` | Java | Uses Java SAX parser |
| `HTML::Parser` | Java | |

### Image Processing

| Module | Implementation | Notes |
|--------|---------------|-------|
| `Image::Magick` | Perl | CLI wrapper; requires `magick` in PATH |

### Date & Time

| Module | Implementation | Notes |
|--------|---------------|-------|
| `Time::HiRes` | Java | `System.nanoTime()` |
| `Time::Piece` | Java + Perl | |
| `Time::Local` | Perl | |
| `DateTime` | Java + Perl | Java backend bundled; install `DateTime` from CPAN with `jcpan -i DateTime` (timezone data gets frequent updates) |
| `POSIX` | Java | Includes `strftime`, `mktime`, etc. |

### Math

| Module | Implementation | Notes |
|--------|---------------|-------|
| `Math::BigInt` | Java | Uses `java.math.BigInteger` |

### Process Control

| Module | Implementation | Notes |
|--------|---------------|-------|
| `IPC::Open2` | Perl | |
| `IPC::Open3` | Java | |
| `IPC::System::Simple` | Perl | |

### Terminal

| Module | Implementation | Notes |
|--------|---------------|-------|
| `Term::ReadKey` | Java | |
| `Term::ReadLine` | Java | |
| `Term::ANSIColor` | Perl | |
| `Term::Table` | Perl | |
| `IO::Tty` | Java + Perl | PTY allocation, terminal constants, winsize ops via FFM |
| `IO::Pty` | Perl | Pseudo-terminal pairs; depends on IO::Tty |
| `IO::Tty::Constant` | Java + Perl | Terminal ioctl constants (TIOCGWINSZ, etc.) |

### OOP & Introspection

| Module | Implementation | Notes |
|--------|---------------|-------|
| `Scalar::Util` | Java | |
| `Sub::Name` | Java | |
| `Sub::Util` | Java | |
| `Class::Struct` | Perl | |
| `Attribute::Handlers` | Perl | |
| `Devel::Cycle` | Perl | |
| `Devel::Peek` | Perl | |
| `Class::MOP` | Perl | Upstream 2.4000 source |
| `Moose` | Perl | Upstream 2.4000 source; ~99% of upstream tests pass (no threads). See note below. |
| `B` | Perl | `svref_2object`, `B::CV`/`GV`/`STASH`, `CVf_ANON`, etc. — enough for `Class::MOP::get_code_info` and `B::Deparse`. |

### Testing

| Module | Implementation | Notes |
|--------|---------------|-------|
| `Test::More` | Perl | |
| `Test::Simple` | Perl | |
| `Test::Builder` | Perl | |
| `Test::Harness` | Perl | |
| `Test2::Suite` | Perl | Full Test2 stack (~100 files) |
| `TAP::Harness` | Perl | Full TAP stack (~43 files) |
| `App::Prove` | Perl | `prove` command |

### Build & Install

| Module | Implementation | Notes |
|--------|---------------|-------|
| `ExtUtils::MakeMaker` | Perl | PerlOnJava-specific version |
| `CPAN` | Perl | Full CPAN client (~30 sub-modules) |
| `CPAN::Meta` | Perl | |
| `Module::Build::Base` | Perl | |
| `Parse::CPAN::Meta` | Perl | |
| `DynaLoader` / `XSLoader` | Java | Routes XS loads to Java implementations |

### Documentation

| Module | Implementation | Notes |
|--------|---------------|-------|
| `Pod::Simple` | Perl | ~22 sub-modules |
| `Pod::Perldoc` | Perl | ~12 sub-modules |
| `Pod::Text` | Perl | + Color, Overstrike, Termcap |
| `Pod::Man` | Perl | |
| `Pod::Usage` | Perl | |
| `Pod::Checker` | Perl | |
| `Pod::Html` | Perl | + `Pod::Html::Util`; `pod2html` POD-to-HTML converter |

### Java Integration

| Module | Implementation | Notes |
|--------|---------------|-------|
| `Java::System` | Java + Perl | Access JVM system properties |

### Miscellaneous

| Module | Implementation | Notes |
|--------|---------------|-------|
| `Getopt::Long` | Perl | |
| `Getopt::Std` | Perl | |
| `Sys::Hostname` | Java | |
| `I18N::Langinfo` | Java | |
| `Benchmark` | Perl | |
| `Filter::Simple` | Perl | |
| `Filter::Util::Call` | Java | |
| `Tie::Hash` / `Tie::Array` / `Tie::Scalar` | Perl | |
| `Tie::RefHash` | Perl | |

---

## Implementation Types

- **Java** — Core functionality implemented in Java for performance or JVM
  integration.  These are in `src/main/java/org/perlonjava/runtime/perlmodule/`.
- **Perl** — Pure-Perl module bundled in the JAR
  (`src/main/perl/lib/`).
- **Java + Perl** — Java provides the XS-equivalent functions; a Perl `.pm`
  file provides the high-level API.

## Adding a New Bundled Module

To bundle a new module into PerlOnJava, see the
**[Module Porting Guide](../guides/module-porting.md)**.  After adding the
module, update this page — add an entry to the appropriate category table
with the module name, implementation type, and any notes about external
requirements.

## See Also

- [Module Porting Guide](../guides/module-porting.md) — How to bundle new modules
- [Using CPAN Modules](../guides/using-cpan-modules.md) — Installing additional modules
- [Database Access Guide](../guides/database-access.md) — DBI + JDBC setup
- [XS Compatibility](xs-compatibility.md) — Status of XS module support
- [Feature Matrix](feature-matrix.md) — Perl language feature support
