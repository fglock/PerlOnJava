# CPAN Phase 6: Full CPAN.pm and Module::Build Support

## Executive Summary

**Goal**: Enable `cpan install Some::Module` to work on PerlOnJava for pure Perl modules.

**Key Finding**: CPAN.pm's only blocker is `Safe.pm`, which is used in 3 places to safely eval CPAN metadata. We can create a stub that just uses regular `eval` since CPAN metadata is trusted.

---

## Analysis

### CPAN.pm Safe Usage

CPAN.pm uses `Safe->new()->reval($code)` in exactly 3 places:

| File | Purpose |
|------|---------|
| `CPAN/Index.pm:521` | Eval CPAN package index |
| `CPAN/Distribution.pm:1575` | Eval CHECKSUMS file |
| `CPAN/Author.pm:193` | Eval author CHECKSUMS |

All three cases eval trusted CPAN metadata (Perl data structures). A stub Safe that uses regular `eval` is sufficient.

### Module::Build

Module::Build is **100% pure Perl** with no XS dependencies. It can be imported directly or stubbed like ExtUtils::MakeMaker.

---

## Implementation Plan

### Phase 6a: Safe.pm Stub (Enables CPAN.pm)

Create a minimal Safe.pm that provides `reval()` without sandboxing:

```perl
package Safe;
use strict;
use warnings;
our $VERSION = '2.44_perlonjava';

sub new {
    my ($class, $namespace) = @_;
    $namespace //= "Safe::Root0";
    bless { namespace => $namespace }, $class;
}

sub reval {
    my ($self, $code) = @_;
    # For PerlOnJava, we trust CPAN metadata - no sandboxing needed
    my $result = eval $code;
    if ($@) {
        die $@;
    }
    return $result;
}

# Stubs for completeness
sub permit { }
sub permit_only { }
sub deny { }
sub deny_only { }
sub mask { }
sub varglob { }
sub root { shift->{namespace} }

1;
```

**Files to create:**
- `src/main/perl/lib/Safe.pm`

**Risk**: Low - CPAN metadata is trusted Perl data structures

### Phase 6b: Module::Build Stub

Like ExtUtils::MakeMaker, intercept `Build.PL` to install pure Perl modules directly:

```perl
package Module::Build;
use strict;
use warnings;
our $VERSION = '0.4234_perlonjava';

sub new {
    my ($class, %args) = @_;
    bless \%args, $class;
}

sub create_build_script {
    my $self = shift;
    # Create a Build script that calls our install
    open my $fh, '>', 'Build' or die "Cannot create Build: $!";
    print $fh "#!/usr/bin/env jperl\n";
    print $fh "use Module::Build;\n";
    print $fh "Module::Build->resume->dispatch;\n";
    close $fh;
    chmod 0755, 'Build';
}

sub resume {
    my $class = shift;
    # Load saved state from _build/
    require Storable;
    return Storable::retrieve('_build/build_params') 
        if -f '_build/build_params';
    die "No _build directory found\n";
}

sub dispatch {
    my $self = shift;
    my $action = shift @ARGV // 'build';
    
    if ($action eq 'build') {
        # No-op for pure Perl
        print "Build complete (pure Perl, no compilation needed)\n";
    }
    elsif ($action eq 'install') {
        $self->_install_pure_perl();
    }
    elsif ($action eq 'test') {
        $self->_run_tests();
    }
}

sub _install_pure_perl {
    my $self = shift;
    # Similar to ExtUtils::MakeMaker implementation
    # Copy lib/*.pm to install directory
}

1;
```

**Files to create:**
- `src/main/perl/lib/Module/Build.pm`
- `src/main/perl/lib/Module/Build/Base.pm` (stub)

### Phase 6c: Import CPAN.pm

Import CPAN.pm and its dependencies via sync.pl:

```yaml
# CPAN.pm core
- source: cpan/CPAN/lib/CPAN.pm
  target: src/main/perl/lib/CPAN.pm
- source: cpan/CPAN/lib/CPAN
  target: src/main/perl/lib/CPAN
  type: directory
```

**Dependencies to verify:**
- ✅ File::Spec, File::Copy, File::Find, File::Path
- ✅ FileHandle, Fcntl, Text::ParseWords, Text::Wrap
- ✅ HTTP::Tiny (for downloads)
- ✅ Archive::Tar, Compress::Zlib
- ✅ Digest::MD5, Digest::SHA
- ✅ Sys::Hostname
- ⚠️ CPAN::Meta (may need import)
- ⚠️ CPAN::Meta::YAML (may need import)

### Phase 6d: Testing

Test with real CPAN modules:

```bash
# Test 1: Simple pure Perl module
jperl -MCPAN -e 'install "Try::Tiny"'

# Test 2: Module with dependencies
jperl -MCPAN -e 'install "Path::Tiny"'

# Test 3: Module::Build based module
jperl -MCPAN -e 'install "Moo"'
```

---

## Alternative: Minimal jcpan Tool

~~If full CPAN.pm is too complex, create a simpler tool:~~

**Update**: With the Safe.pm stub, we can use the **original CPAN.pm** directly:

```bash
# Use original cpan command
jperl -MCPAN -e 'install "Path::Tiny"'

# Or interactive shell
jperl -MCPAN -e shell
```

This ensures 100% compatibility with the standard `cpan` tool.

### Why Not a Custom jcpan?

| Approach | Pros | Cons |
|----------|------|------|
| Original CPAN.pm | 100% compatible, well-tested, familiar | Larger dependency set |
| Custom jcpan | Minimal, simple | Different behavior, maintenance burden |

**Recommendation**: Use original CPAN.pm. The Safe.pm stub is the only missing piece.

### Minimal Wrapper Script (Optional)

For convenience, create a `jcpan` wrapper:

```bash
#!/bin/bash
# jcpan - CPAN client for PerlOnJava
exec jperl -MCPAN -e "CPAN::shell()" -- "$@"
```

Or for non-interactive use:

```bash
#!/bin/bash
# jcpan install Module::Name
ACTION=${1:-help}
MODULE=$2

case $ACTION in
    install)
        jperl -MCPAN -e "install '$MODULE'"
        ;;
    search)
        jperl -MCPAN -e "CPAN::Shell->m('/$MODULE/')"
        ;;
    *)
        echo "Usage: jcpan install Module::Name"
        echo "       jcpan search keyword"
        ;;
esac
```

This gives users the familiar `jcpan install Foo` syntax while using the original CPAN.pm underneath.

---

## Revised Implementation Order

With the Safe.pm stub approach, we can use the original CPAN.pm:

1. **Safe.pm stub** - 1 hour, unlocks CPAN.pm
2. **Import CPAN.pm** - 2 hours, add to sync.pl config
3. **Module::Build stub** - 2 hours, supports Build.PL modules
4. **Try::Tiny shim** - 1 hour, compatibility for common module
5. **jcpan wrapper script** - 30 min, convenience wrapper
6. **Testing & documentation** - 2 hours

**Total**: ~8.5 hours for full CPAN support

---

## Files to Create

| File | Purpose |
|------|---------|
| `src/main/perl/lib/Safe.pm` | Stub for CPAN.pm |
| `src/main/perl/lib/Try/Tiny.pm` | Compatibility shim |
| `src/main/perl/lib/Module/Build.pm` | Build.PL support |
| `src/main/perl/lib/Module/Build/Base.pm` | Base class stub |
| `bin/jcpan` | Wrapper script for `jperl -MCPAN` |
| `dev/import-perl5/config.yaml` | Add CPAN.pm imports |

---

## Success Criteria

- [ ] `jperl -MSafe -e 'print Safe->new->reval("1+1")'` prints 2
- [ ] `jperl -MCPAN -e 'print $CPAN::VERSION'` works
- [ ] `jperl -MCPAN -e 'install "Path::Tiny"'` installs successfully
- [ ] `jperl -MTry::Tiny -e 'try { die "x" } catch { print "caught" }'` works
- [ ] Module::Build based modules can be installed

**Note**: Some modules won't work due to unsupported features:
- Modules using `DESTROY` for cleanup (use `defer` or explicit cleanup)
- Modules using `fork` (use IPC::Open2/Open3)
- Modules using `threads` (not implemented)

---

## Phase 6e: Try::Tiny Compatibility Shim

Create a `Try::Tiny` replacement that uses the built-in `try` feature:

```perl
package Try::Tiny;
use strict;
use warnings;
use feature 'try';
no warnings 'experimental::try';

our $VERSION = '0.31_perlonjava';

use Exporter 'import';
our @EXPORT = qw(try catch finally);
our @EXPORT_OK = @EXPORT;

# try BLOCK catch BLOCK
# try BLOCK catch BLOCK finally BLOCK
# try BLOCK finally BLOCK

sub try (&;@) {
    my ($try, @handlers) = @_;
    
    my ($catch, $finally);
    for my $h (@handlers) {
        if (ref $h eq 'Try::Tiny::Catch') {
            $catch = $$h;
        }
        elsif (ref $h eq 'Try::Tiny::Finally') {
            $finally = $$h;
        }
    }
    
    my ($ok, $error, @result);
    {
        local $@;
        $ok = eval {
            @result = $try->();
            1;
        };
        $error = $@;
    }
    
    if (!$ok && $catch) {
        local $_ = $error;
        @result = $catch->($error);
    }
    
    if ($finally) {
        $finally->();
    }
    
    return wantarray ? @result : $result[0];
}

sub catch (&;@) {
    my ($block, @rest) = @_;
    return (bless(\$block, 'Try::Tiny::Catch'), @rest);
}

sub finally (&;@) {
    my ($block, @rest) = @_;
    return (bless(\$block, 'Try::Tiny::Finally'), @rest);
}

1;
```

**Usage** (identical to original Try::Tiny):
```perl
use Try::Tiny;

try {
    die "oops";
}
catch {
    warn "caught: $_";
}
finally {
    print "cleanup\n";
};
```

**Files to create:**
- `src/main/perl/lib/Try/Tiny.pm`

**Benefits:**
- Drop-in replacement for existing code using Try::Tiny
- No DESTROY needed - uses eval-based approach
- Supports try/catch/finally syntax

---

## Open Questions

1. Should jcpan auto-install dependencies recursively?
2. Should we support cpanm (requires Safe + many more modules)?
3. How to handle test failures during install?
