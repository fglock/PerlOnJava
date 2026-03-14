# Using CPAN Modules with PerlOnJava

## Overview

PerlOnJava includes many common CPAN modules and supports adding additional pure Perl modules to your projects. It also includes a custom `ExtUtils::MakeMaker` that allows you to install pure Perl CPAN modules directly without needing `make` or native compilation.

## Quick Start: Installing a CPAN Module

For pure Perl modules with a standard `Makefile.PL`:

```bash
# Download and extract the module
curl -O https://cpan.metacpan.org/authors/id/X/XX/AUTHOR/Module-Name-1.00.tar.gz
tar xzf Module-Name-1.00.tar.gz
cd Module-Name-1.00

# Install with jperl (no make needed!)
jperl Makefile.PL

# The module is now installed to ~/.perlonjava/lib/
# and automatically available in @INC
```

## Checking Module Availability

To check if a module is available in PerlOnJava:

```bash
./jperl -e 'use Module::Name; print "Available\n"'
```

## Available Modules

PerlOnJava includes:

### Core Modules
- `strict`, `warnings`, `utf8`, `feature`
- `Carp`, `Config`, `Cwd`, `Exporter`
- `File::Spec`, `File::Basename`, `File::Copy`, `File::Find`, `File::Path`, `File::Temp`
- `IO::File`, `IO::Handle`, `FileHandle`, `DirHandle`
- `Getopt::Long`, `Getopt::Std`
- `Sys::Hostname` - System hostname
- `Symbol` - Symbol manipulation

### Process Control
- `IPC::Open2`, `IPC::Open3` - Bi-directional process communication

### Build Tools
- `ExtUtils::MakeMaker` - Module installation (PerlOnJava version)

### Data Processing
- `JSON` - JSON encoding/decoding
- `YAML` - YAML parsing
- `TOML` - TOML parsing
- `Text::CSV` - CSV parsing
- `Data::Dumper` - Data structure dumping
- `Storable` - Data serialization

### Cryptography & Encoding
- `Digest::MD5`, `Digest::SHA` - Hash algorithms
- `MIME::Base64`, `MIME::QuotedPrint` - Encoding
- `Encode` - Character encoding

### Network & Web
- `HTTP::Tiny` - HTTP client
- `Socket` - Low-level socket support
- `IO::Socket::INET`, `IO::Socket::UNIX` - TCP/IP and Unix sockets
- `Net::FTP` - FTP client
- `Net::SMTP` - SMTP client
- `Net::POP3` - POP3 client
- `Net::NNTP` - NNTP client

### Archives
- `Archive::Tar` - Tar file handling
- `Archive::Zip` - Zip file handling
- `Compress::Zlib` - Compression
- `IO::Zlib` - Compressed I/O

### Database
- `DBI` - Database interface (with JDBC backend)

### Testing
- `Test::More`, `Test::Simple`, `Test::Builder`

### Time
- `Time::HiRes` - High-resolution time
- `Time::Piece` - Time manipulation
- `POSIX` - POSIX functions including strftime

## Adding Pure Perl Modules

If you need a CPAN module that's not included, you can often add pure Perl modules directly.

### Method 1: ExtUtils::MakeMaker (Recommended)

PerlOnJava includes a custom `ExtUtils::MakeMaker` that installs pure Perl modules directly:

```bash
# Download and extract
tar xzf Some-Module-1.00.tar.gz
cd Some-Module-1.00

# Run Makefile.PL with jperl
jperl Makefile.PL
```

**What happens:**
- For **pure Perl modules**: `.pm` files are copied to `~/.perlonjava/lib/`
- For **XS modules**: You'll see guidance on porting options

**Customizing the install location:**
```bash
# Install to a specific directory
PERLONJAVA_LIB=/path/to/my/libs jperl Makefile.PL
```

The default `~/.perlonjava/lib/` directory is automatically included in `@INC`, so installed modules work immediately.

### Method 2: Local lib Directory

Create a `lib` directory in your project and add modules there:

```bash
mkdir -p myproject/lib
cp /path/to/Some/Module.pm myproject/lib/Some/Module.pm
```

Run with:

```bash
./jperl -Imyproject/lib myscript.pl
```

### Method 3: PERL5LIB Environment Variable

```bash
export PERL5LIB=/path/to/your/modules
./jperl myscript.pl
```

### Finding Pure Perl Modules

To check if a CPAN module is pure Perl:

1. Visit https://metacpan.org/pod/Module::Name
2. Look at the source files
3. If there's only `.pm` files (no `.xs` or `.c` files), it's pure Perl

### Example: Adding a Simple Module

```bash
# Download a module from CPAN
curl -O https://cpan.metacpan.org/authors/id/X/XX/XXXX/Module-Name-1.00.tar.gz

# Extract
tar xzf Module-Name-1.00.tar.gz

# Copy the lib directory
cp -r Module-Name-1.00/lib/* myproject/lib/
```

## Modules with XS Components

Some CPAN modules have XS (C/C++) components that won't work directly. PerlOnJava's `ExtUtils::MakeMaker` automatically detects XS modules and provides guidance:

```
XS MODULE DETECTED: Some::XS::Module
============================================================

This module contains XS/C code that cannot be used directly.
PerlOnJava compiles to JVM bytecode, not native code.

XS/C files found:
  - Module.xs

Options:
  1. Check if PerlOnJava already has a Java implementation
  2. Look for a pure Perl alternative module on CPAN
  3. Port the XS code to Java
```

For XS modules, your options are:

1. **Check if PerlOnJava has a Java port** - Many common XS modules have Java implementations
2. **Look for pure Perl alternatives** - e.g., use `JSON` instead of `JSON::XS`
3. **Request a port** - Open an issue at the PerlOnJava repository

### Common XS Modules with Java Alternatives

| XS Module | Java Alternative in PerlOnJava |
|-----------|-------------------------------|
| JSON::XS | JSON (built-in) |
| Compress::Raw::Zlib | Compress::Zlib (built-in) |
| Digest::MD5 (XS part) | Digest::MD5 (Java implementation) |
| DBI (XS part) | DBI (JDBC backend) |
| Time::HiRes (XS part) | Time::HiRes (Java implementation) |

## Working with Archive Files

### Reading a Zip File

```perl
use Archive::Zip qw(:ERROR_CODES);

my $zip = Archive::Zip->new();
my $status = $zip->read('archive.zip');
die "Read failed" unless $status == AZ_OK;

for my $member ($zip->members()) {
    print $member->fileName(), "\n";
}
```

### Creating a Zip File

```perl
use Archive::Zip qw(:ERROR_CODES);

my $zip = Archive::Zip->new();
$zip->addFile('document.txt');
$zip->addString("Hello!", 'hello.txt');

my $status = $zip->writeToFileNamed('output.zip');
```

### Working with Tar Files

```perl
use Archive::Tar;

# Read
my $tar = Archive::Tar->new('archive.tar.gz');
my @files = $tar->list_files();
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
my $response = $http->get('https://api.github.com/repos/perl/perl5');

if ($response->{success}) {
    print $response->{content};
}
```

## Downloading and Extracting CPAN Modules

Here's a helper script to download and extract a CPAN module:

```perl
#!/usr/bin/env jperl
use strict;
use warnings;
use HTTP::Tiny;
use Archive::Tar;
use File::Temp qw(tempfile);
use File::Path qw(make_path);

my $module = shift or die "Usage: $0 Module::Name\n";
my $dest = shift || 'lib';

# Convert module name to path
(my $path = $module) =~ s/::/-/g;

# Query MetaCPAN for download URL
my $http = HTTP::Tiny->new();
my $resp = $http->get("https://fastapi.metacpan.org/v1/download_url/$module");

if (!$resp->{success}) {
    die "Could not find $module on CPAN\n";
}

# Parse JSON response
use JSON;
my $data = decode_json($resp->{content});
my $url = $data->{download_url};

print "Downloading $url\n";
my $tarball = $http->get($url);

if (!$tarball->{success}) {
    die "Download failed\n";
}

# Save to temp file
my ($fh, $filename) = tempfile(SUFFIX => '.tar.gz');
print $fh $tarball->{content};
close $fh;

# Extract
my $tar = Archive::Tar->new($filename);
my @files = $tar->list_files();

make_path($dest);
for my $file (@files) {
    next unless $file =~ m{/lib/(.+\.pm)$};
    my $target = "$dest/$1";
    my $dir = $target;
    $dir =~ s{/[^/]+$}{};
    make_path($dir);
    
    my $content = $tar->get_content($file);
    open my $out, '>', $target or die "Cannot write $target: $!";
    print $out $content;
    close $out;
    print "Installed $target\n";
}

unlink $filename;
print "Done!\n";
```

## Unsupported Perl Features

Some CPAN modules use Perl features that are not yet implemented in PerlOnJava:

| Feature | Status | Affected Modules |
|---------|--------|------------------|
| `DESTROY` | Not implemented | Try::Tiny, Scope::Guard, Guard |
| `fork` | Not available on JVM | Parallel::*, POE, AnyEvent::Fork |
| `threads` | Not implemented | threads, Thread::Queue |
| XS/C code | Requires Java port | JSON::XS, Cpanel::JSON::XS |

### Workarounds

- **DESTROY**: Use `defer` blocks (Perl 5.36+) or explicit cleanup
- **fork**: Use `IPC::Open2`/`IPC::Open3` for subprocess communication
- **threads**: Use Java threading via inline Java (advanced)
- **XS modules**: Check if PerlOnJava has a Java port, or use pure Perl alternatives

### Example: Try::Tiny Alternative

`Try::Tiny` uses `DESTROY` for scope guards. Use native `try`/`catch` instead:

```perl
use feature 'try';

try {
    risky_operation();
}
catch ($e) {
    warn "Error: $e";
}
```

## Troubleshooting

### "Can't locate Module.pm in @INC"

The module is not installed. Check:
1. Is it a pure Perl module? XS modules won't work directly.
2. Is the module in your lib path? Use `-I/path/to/lib`

### "Can't load Java XS module"

This means the module requires an XS implementation that hasn't been ported to Java. Check if there's a pure Perl alternative.

### Module loads but functions don't work

Some modules may load but have unsupported features:
- Check if the module uses XS functions internally
- Some Perl built-in functions may not be fully implemented

## Getting Help

- **PerlOnJava Repository**: https://github.com/fglock/PerlOnJava
- **Issues**: Report missing modules or compatibility problems
- **Feature Matrix**: See `docs/reference/feature-matrix.md` for supported features
