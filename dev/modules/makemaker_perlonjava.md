# ExtUtils::MakeMaker for PerlOnJava - Design Document

## Overview

Create a PerlOnJava-specific ExtUtils::MakeMaker that:
1. **Pure Perl modules**: Directly installs by copying .pm files
2. **XS modules**: Detects and flags for manual/LLM-assisted resolution

## How Traditional MakeMaker Works

```
Makefile.PL → WriteMakefile() → Makefile → make → make install
```

Key attributes parsed from Makefile.PL:
- `NAME` - Module name (e.g., 'Some::Module')
- `VERSION` / `VERSION_FROM` - Module version
- `PREREQ_PM` - Dependencies
- `XS` - Hash of XS files to compile
- `PM` - Hash of .pm files to install
- `EXE_FILES` - Scripts to install
- `LIBS` / `INC` - C library flags (XS only)

## PerlOnJava Approach

```
Makefile.PL → WriteMakefile() → Direct Install (or XS detection)
```

### Phase 1: Basic Implementation

```perl
package ExtUtils::MakeMaker;
use strict;
use warnings;

our $VERSION = '7.70_perlonjava';

use Exporter 'import';
our @EXPORT = qw(WriteMakefile prompt);

use File::Copy;
use File::Path qw(make_path);
use File::Find;
use File::Spec;
use Cwd;

# Installation directory (configurable)
our $INSTALL_BASE = $ENV{PERLONJAVA_LIB} || './lib';

sub WriteMakefile {
    my %args = @_;
    
    my $name = $args{NAME} or die "NAME is required\n";
    my $version = $args{VERSION} || $args{VERSION_FROM} && _extract_version($args{VERSION_FROM});
    
    print "PerlOnJava MakeMaker: $name v$version\n";
    
    # Check for XS files
    my @xs_files = _find_xs_files(\%args);
    
    if (@xs_files) {
        return _handle_xs_module($name, \@xs_files, \%args);
    }
    
    # Pure Perl - proceed with installation
    return _install_pure_perl(\%args);
}

sub _find_xs_files {
    my ($args) = @_;
    my @xs;
    
    # Explicit XS hash
    if ($args->{XS}) {
        push @xs, keys %{$args->{XS}};
    }
    
    # Scan for .xs files
    find(sub {
        push @xs, $File::Find::name if /\.xs$/;
    }, '.');
    
    return @xs;
}

sub _handle_xs_module {
    my ($name, $xs_files, $args) = @_;
    
    print "\n";
    print "=" x 60, "\n";
    print "XS MODULE DETECTED: $name\n";
    print "=" x 60, "\n";
    print "\n";
    print "This module contains XS/C code that requires porting to Java:\n";
    for my $xs (@$xs_files) {
        print "  - $xs\n";
    }
    print "\n";
    print "Options:\n";
    print "  1. Check if PerlOnJava already has a Java port\n";
    print "  2. Use the port-cpan-module skill to create a Java implementation\n";
    print "  3. Find a pure Perl alternative module\n";
    print "\n";
    
    # Return a stub MM object
    return PerlOnJava::MM::XSStub->new($name, $xs_files, $args);
}

sub _install_pure_perl {
    my ($args) = @_;
    
    my %pm;
    
    # Use explicit PM hash or scan lib/
    if ($args->{PM}) {
        %pm = %{$args->{PM}};
    } else {
        # Default: lib/**/*.pm → $(INST_LIB)
        find(sub {
            return unless /\.pm$/;
            my $src = $File::Find::name;
            (my $dest = $src) =~ s{^lib/}{};
            $pm{$src} = File::Spec->catfile($INSTALL_BASE, $dest);
        }, 'lib') if -d 'lib';
    }
    
    # Install .pm files
    for my $src (sort keys %pm) {
        my $dest = $pm{$src};
        my $dir = File::Spec->catpath((File::Spec->splitpath($dest))[0,1], '');
        make_path($dir) unless -d $dir;
        
        print "Installing $src → $dest\n";
        copy($src, $dest) or warn "Failed to copy $src: $!\n";
    }
    
    # Install scripts
    if ($args->{EXE_FILES}) {
        for my $script (@{$args->{EXE_FILES}}) {
            print "Installing script: $script\n";
            # Copy to bin directory
        }
    }
    
    print "\nInstallation complete!\n";
    
    return PerlOnJava::MM::Installed->new($args);
}

sub _extract_version {
    my ($file) = @_;
    open my $fh, '<', $file or return '0';
    while (<$fh>) {
        if (/\$VERSION\s*=\s*['"]?([\d._]+)/) {
            return $1;
        }
    }
    return '0';
}

sub prompt {
    my ($msg, $default) = @_;
    $default //= '';
    print "$msg [$default] ";
    my $answer = <STDIN>;
    chomp $answer if defined $answer;
    return (defined $answer && $answer ne '') ? $answer : $default;
}

# Stub object for installed modules
package PerlOnJava::MM::Installed;
sub new { bless { args => $_[1] }, $_[0] }
sub flush { 1 }

# Stub object for XS modules (not installed)
package PerlOnJava::MM::XSStub;
sub new { 
    my ($class, $name, $xs_files, $args) = @_;
    bless { name => $name, xs => $xs_files, args => $args }, $class;
}
sub flush { 
    my $self = shift;
    print "Skipping XS module: $self->{name}\n";
    0;
}

1;
```

### Phase 2: LLM Integration

For XS modules, optionally call an LLM to generate Java implementations:

```perl
sub _handle_xs_module {
    my ($name, $xs_files, $args) = @_;
    
    if ($ENV{PERLONJAVA_AUTO_PORT}) {
        print "Attempting automatic XS → Java port...\n";
        
        for my $xs (@$xs_files) {
            my $xs_content = _read_file($xs);
            my $java_code = _call_llm_for_port($name, $xs_content);
            
            if ($java_code) {
                _write_java_module($name, $java_code);
                print "Generated Java implementation for $xs\n";
            }
        }
    }
    
    # ... rest of handling
}

sub _call_llm_for_port {
    my ($module_name, $xs_content) = @_;
    
    # Call external LLM API (Claude, GPT, etc.)
    # Using the port-cpan-module skill's knowledge
    
    my $prompt = <<"END_PROMPT";
Port this Perl XS code to Java for PerlOnJava.

Module: $module_name

XS Code:
$xs_content

Create a Java class extending PerlModuleBase with:
1. Constructor calling super("$module_name", false)
2. Static initialize() method registering all methods
3. Static methods with signature: RuntimeList methodName(RuntimeArray args, int ctx)
END_PROMPT

    # HTTP call to LLM API
    # ...
    
    return $java_code;
}
```

### Phase 3: Dependency Resolution

```perl
sub WriteMakefile {
    my %args = @_;
    
    # Check prerequisites first
    if ($args{PREREQ_PM}) {
        my @missing;
        for my $dep (keys %{$args{PREREQ_PM}}) {
            my $version = $args{PREREQ_PM}{$dep};
            unless (_module_available($dep, $version)) {
                push @missing, "$dep (>= $version)";
            }
        }
        
        if (@missing) {
            print "Missing dependencies:\n";
            print "  - $_\n" for @missing;
            
            if ($ENV{PERLONJAVA_AUTO_DEPS}) {
                _install_dependencies(@missing);
            } else {
                print "\nInstall with: jcpan install @missing\n";
                return;
            }
        }
    }
    
    # ... continue with installation
}
```

## Implementation Plan

### Phase 1: Core Functionality
1. Create `ExtUtils/MakeMaker.pm` that intercepts `WriteMakefile()`
2. Implement pure Perl module installation (copy .pm files)
3. Detect XS files and print helpful message
4. Handle `PREREQ_PM` dependency checking

### Phase 2: XS Detection & Guidance
1. Parse XS files to identify functions
2. Generate skeleton Java code
3. Create issue/task for manual porting
4. Integration with port-cpan-module skill

### Phase 3: LLM Integration (Future)
1. API integration for LLM calls
2. XS → Java translation prompts
3. Verification and testing of generated code
4. Human review workflow

## Files to Create

1. `src/main/perl/lib/ExtUtils/MakeMaker.pm` - Main module
2. `src/main/perl/lib/ExtUtils/MM.pm` - Stub for compatibility
3. `src/main/perl/lib/ExtUtils/MY.pm` - Stub for compatibility
4. `src/main/perl/lib/ExtUtils/MakeMaker/Config.pm` - Config wrapper

## Compatibility Notes

- `perl Makefile.PL` should work and either install or report XS
- `make` and `make install` become no-ops (or print helpful message)
- `PREREQ_PM` dependencies are checked but not auto-installed (Phase 1)
- No actual Makefile is generated (no need for `make`)

## Example Usage

```bash
# Download a CPAN module
tar xzf Some-Module-1.00.tar.gz
cd Some-Module-1.00

# For pure Perl:
jperl Makefile.PL
# → "Installing lib/Some/Module.pm → /path/to/lib/Some/Module.pm"
# → "Installation complete!"

# For XS modules:
jperl Makefile.PL
# → "XS MODULE DETECTED: Some::Module"
# → "This module contains XS/C code..."
# → Lists .xs files and options
```

## Environment Variables

- `PERLONJAVA_LIB` - Installation directory (default: ./lib)
- `PERLONJAVA_AUTO_PORT` - Enable automatic LLM-based XS porting
- `PERLONJAVA_AUTO_DEPS` - Automatically install dependencies
- `PERLONJAVA_VERBOSE` - Verbose output

## Related Documents

- `cpan_client.md` - CPAN client status
- `docs/guides/module-porting.md` - Module porting guide
- `.agents/skills/port-cpan-module/` - Port CPAN module skill
