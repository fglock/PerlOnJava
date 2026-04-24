# Using CPAN Modules with PerlOnJava

## Overview

PerlOnJava includes many common CPAN modules and supports installing additional modules from CPAN. It provides `jcpan`, a CPAN client that works with PerlOnJava, and a custom `ExtUtils::MakeMaker` that handles module installation without requiring native compilation tools.

## Quick Start: Installing Modules with jcpan

The recommended way to install CPAN modules is using `jcpan`:

```bash
# Install a module
jcpan install Try::Tiny

# Install with force (ignore test failures)
jcpan -f install Module::Name

# Run a module's test suite
jcpan -t Module::Name

# Interactive CPAN shell
jcpan
```

Modules are installed to `~/.perlonjava/lib/`, which is automatically included in `@INC`.

## Manual Installation

For modules not on CPAN or when you need more control:

```bash
# Download and extract
curl -O https://cpan.metacpan.org/authors/id/X/XX/AUTHOR/Module-Name-1.00.tar.gz
tar xzf Module-Name-1.00.tar.gz
cd Module-Name-1.00

# Install with jperl (no make needed)
jperl Makefile.PL
```

## Module Compatibility

### Pure Perl Modules

Pure Perl modules (those with only `.pm` files) work directly with PerlOnJava. Most CPAN modules fall into this category.

### XS Modules

XS modules contain C code that must be compiled to native machine code. Since PerlOnJava compiles to JVM bytecode rather than native code, XS modules require special handling.

When you install an XS module, PerlOnJava's MakeMaker:

1. Detects the XS/C files
2. Installs the `.pm` files (but not the XS code)
3. Prints guidance about what will happen at runtime

At runtime, when the module calls `XSLoader::load()`:

1. **Java implementation available**: If PerlOnJava includes a Java implementation of the XS functions, it loads automatically
2. **Pure Perl fallback**: If no Java implementation exists, XSLoader returns an error that many modules catch to fall back to a pure Perl implementation
3. **No fallback**: If the module has no fallback, it fails to load

Many popular XS modules include their own pure Perl fallbacks that activate automatically. The module's `.pm` file handles this transparently.

For a complete list of XS modules and their compatibility status, see the [XS Compatibility Reference](../reference/xs-compatibility.md).

### Built-in Java Implementations

PerlOnJava includes Java implementations of XS functions for several modules:

| Module | Notes |
|--------|-------|
| DateTime | Uses java.time APIs with JulianFields.RATA_DIE |
| JSON | Delegates to the bundled pure-Perl `JSON::PP` for full `JSON::XS` option parity |
| Digest::MD5 | Java MessageDigest API |
| Digest::SHA | Java MessageDigest API |
| Time::HiRes | Java System.nanoTime() |
| DBI | JDBC backend |
| Compress::Zlib | Java zip libraries |

When these modules are loaded, the Java implementation is used automatically for best performance.

## Checking Module Availability

```bash
# Check if a module is available
./jperl -e 'use Module::Name; print "Available\n"'

# Check if a module uses XS
./jperl -e 'use Module::Name; print Module::Name->can("is_xs") ? "XS" : "PP"'
```

## Available Built-in Modules

PerlOnJava includes 150+ modules without installation. The highlights are
listed below; for the complete list see
**[Bundled Modules Reference](../reference/bundled-modules.md)**.

### Core Modules
- `strict`, `warnings`, `utf8`, `feature`
- `Carp`, `Config`, `Cwd`, `Exporter`
- `File::Spec`, `File::Basename`, `File::Copy`, `File::Find`, `File::Path`, `File::Temp`
- `IO::File`, `IO::Handle`, `FileHandle`, `DirHandle`
- `Getopt::Long`, `Getopt::Std`
- `Sys::Hostname`, `Symbol`

### Process Control
- `IPC::Open2`, `IPC::Open3`

### Build Tools
- `ExtUtils::MakeMaker` (PerlOnJava version)

### Data Processing
- `JSON` - JSON encoding/decoding
- `YAML` - YAML parsing
- `TOML` - TOML parsing
- `Text::CSV` - CSV parsing
- `Data::Dumper` - Data structure dumping
- `Storable` - Data serialization

### Cryptography & Encoding
- `Digest::MD5`, `Digest::SHA`
- `MIME::Base64`, `MIME::QuotedPrint`
- `Encode`

### Network & Web
- `HTTP::Tiny`
- `Socket`
- `IO::Socket::INET`, `IO::Socket::UNIX`
- `Net::FTP`, `Net::SMTP`, `Net::POP3`, `Net::NNTP`

### Archives
- `Archive::Tar`, `Archive::Zip`
- `Compress::Zlib`, `IO::Zlib`

### Database
- `DBI` (with JDBC backend)

### Testing
- `Test::More`, `Test::Simple`, `Test::Builder`

### Time
- `Time::HiRes`, `Time::Piece`
- `POSIX` (including strftime)

## Alternative Installation Methods

### Local lib Directory

```bash
mkdir -p myproject/lib
cp -r Module-Name/lib/* myproject/lib/
./jperl -Imyproject/lib myscript.pl
```

### PERL5LIB Environment Variable

```bash
export PERL5LIB=/path/to/your/modules
./jperl myscript.pl
```

### Custom Install Location

```bash
PERLONJAVA_LIB=/path/to/libs jperl Makefile.PL
```

## Finding Pure Perl Alternatives

When an XS module doesn't work and has no fallback:

1. Visit https://metacpan.org/pod/Module::Name
2. Check the source files - if there are `.xs` or `.c` files, it's XS
3. Search for pure Perl alternatives (often named `Module::Name::PP` or `Module::Name::Pure`)

Common substitutions:

| XS Module | Pure Perl Alternative |
|-----------|----------------------|
| JSON::XS | JSON (built-in) or JSON::PP |
| List::Util (XS parts) | List::Util::PP |
| Params::Util | Params::Util::PP |

## Working with Archives

### Zip Files

```perl
use Archive::Zip qw(:ERROR_CODES);

# Read
my $zip = Archive::Zip->new();
$zip->read('archive.zip') == AZ_OK or die;
print $_->fileName, "\n" for $zip->members();

# Create
my $zip = Archive::Zip->new();
$zip->addFile('document.txt');
$zip->writeToFileNamed('output.zip');
```

### Tar Files

```perl
use Archive::Tar;

# Read
my $tar = Archive::Tar->new('archive.tar.gz');
$tar->extract();

# Create
my $tar = Archive::Tar->new();
$tar->add_files('file1.txt', 'file2.txt');
$tar->write('output.tar.gz', COMPRESS_GZIP);
```

## HTTP Requests

```perl
use HTTP::Tiny;

my $http = HTTP::Tiny->new();
my $response = $http->get('https://api.example.com/data');

if ($response->{success}) {
    print $response->{content};
}
```

## Troubleshooting

### "Can't locate Module.pm in @INC"

The module is not installed:

1. Install it: `jcpan install Module::Name`
2. Or add it to your lib path: `./jperl -I/path/to/lib script.pl`

### "Can't load loadable object for module X"

This indicates an XS module without a Java implementation or pure Perl fallback. Options:

1. Check if PerlOnJava has a built-in alternative (see table above)
2. Look for a pure Perl alternative on CPAN
3. Request a Java implementation via GitHub issues

### Module loads but functions don't work

Some modules may partially work:

- Check if specific functions require XS (look for `XSLoader::load` in the source)
- Some Perl built-ins may not be fully implemented - check the feature matrix

### Installation succeeds but module fails at runtime

For XS modules, installation only copies `.pm` files. The XS functions aren't available unless:

- PerlOnJava has a Java implementation
- The module has a pure Perl fallback

Check the module's documentation for fallback behavior.

## See Also

- [Bundled Modules Reference](../reference/bundled-modules.md) - Complete list of included modules
- [XS Compatibility Reference](../reference/xs-compatibility.md) - Detailed XS module compatibility
- [Module Porting Guide](module-porting.md) - How to port modules to PerlOnJava
- [Feature Matrix](../reference/feature-matrix.md) - Perl feature compatibility

## Getting Help

- **PerlOnJava Repository**: https://github.com/fglock/PerlOnJava
- **Issues**: Report missing modules or compatibility problems
