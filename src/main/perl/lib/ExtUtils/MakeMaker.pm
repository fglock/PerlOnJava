package ExtUtils::MakeMaker;
use strict;
use warnings;

our $VERSION = '7.70_perlonjava';

use Exporter 'import';
our @EXPORT = qw(WriteMakefile prompt);
our @EXPORT_OK = qw(neatvalue);

use File::Copy;
use File::Path qw(make_path);
use File::Find;
use File::Spec;
use File::Basename;
use Cwd qw(getcwd abs_path);

# Installation directory (configurable via environment)
our $INSTALL_BASE = $ENV{PERLONJAVA_LIB};

# Find the default lib directory
sub _default_install_base {
    # Check if running from JAR
    if ($ENV{PERLONJAVA_JAR}) {
        my $jar_dir = dirname($ENV{PERLONJAVA_JAR});
        return File::Spec->catdir($jar_dir, 'lib');
    }
    # Use ~/.perlonjava/lib as default user library path
    my $home = $ENV{HOME} || $ENV{USERPROFILE} || '.';
    return File::Spec->catdir($home, '.perlonjava', 'lib');
}

sub WriteMakefile {
    my %args = @_;
    
    my $name = $args{NAME} or die "NAME is required\n";
    my $version = $args{VERSION} || ($args{VERSION_FROM} && _extract_version($args{VERSION_FROM})) || '0';
    
    print "PerlOnJava MakeMaker: $name v$version\n";
    print "=" x 60, "\n";
    
    # Set install base if not set
    $INSTALL_BASE //= _default_install_base();
    
    # Check prerequisites first
    if ($args{PREREQ_PM}) {
        my @missing = _check_prereqs($args{PREREQ_PM});
        if (@missing) {
            print "\nMissing dependencies:\n";
            print "  - $_\n" for @missing;
            print "\nPlease install these modules first.\n";
            print "(PerlOnJava uses bundled modules or pure Perl CPAN modules)\n\n";
            # Continue anyway - let the module fail at runtime if needed
        }
    }
    
    # Check for XS files
    my @xs_files = _find_xs_files(\%args);
    
    if (@xs_files) {
        return _handle_xs_module($name, \@xs_files, \%args);
    }
    
    # Pure Perl - proceed with installation
    return _install_pure_perl($name, $version, \%args);
}

sub _check_prereqs {
    my ($prereqs) = @_;
    my @missing;
    
    for my $module (sort keys %$prereqs) {
        my $version = $prereqs->{$module};
        my $found = eval "require $module; 1";
        if (!$found) {
            push @missing, "$module (>= $version)";
        } elsif ($version) {
            # Check version
            my $installed = eval "\$${module}::VERSION" || 0;
            if (_version_compare($installed, $version) < 0) {
                push @missing, "$module (>= $version, have $installed)";
            }
        }
    }
    
    return @missing;
}

sub _version_compare {
    my ($v1, $v2) = @_;
    # Simple numeric comparison - handles most cases
    $v1 =~ s/_//g;
    $v2 =~ s/_//g;
    return ($v1 <=> $v2);
}

sub _find_xs_files {
    my ($args) = @_;
    my @xs;
    
    # Explicit XS hash
    if ($args->{XS}) {
        push @xs, keys %{$args->{XS}};
    }
    
    # C files that indicate XS
    if ($args->{C}) {
        push @xs, @{$args->{C}};
    }
    
    # Scan for .xs and .c files
    my $cwd = getcwd();
    find({
        wanted => sub {
            return unless -f;
            push @xs, $File::Find::name if /\.xs$/ || /\.c$/;
        },
        no_chdir => 1,
    }, $cwd);
    
    return @xs;
}

sub _handle_xs_module {
    my ($name, $xs_files, $args) = @_;
    
    print "\n";
    print "XS MODULE DETECTED: $name\n";
    print "=" x 60, "\n";
    print "\n";
    print "This module contains XS/C code that cannot be used directly.\n";
    print "PerlOnJava compiles to JVM bytecode, not native code.\n\n";
    
    print "XS/C files found:\n";
    for my $xs (sort @$xs_files) {
        print "  - $xs\n";
    }
    print "\n";
    
    print "Options:\n";
    print "  1. Check if PerlOnJava already has a Java implementation\n";
    print "     (Many common XS modules are pre-ported)\n\n";
    print "  2. Look for a pure Perl alternative module on CPAN\n\n";
    print "  3. Port the XS code to Java:\n";
    print "     - Use the port-cpan-module skill in Devin\n";
    print "     - Create a Java class extending PerlModuleBase\n";
    print "     - Register it with XSLoader\n\n";
    
    print "See: docs/guides/using-cpan-modules.md\n";
    print "=" x 60, "\n\n";
    
    # Return a stub MM object
    return PerlOnJava::MM::XSStub->new($name, $xs_files, $args);
}

sub _install_pure_perl {
    my ($name, $version, $args) = @_;
    
    my %pm;
    
    # Use explicit PM hash if provided
    if ($args->{PM}) {
        %pm = %{$args->{PM}};
    } else {
        # Default: scan lib/ directory
        if (-d 'lib') {
            find({
                wanted => sub {
                    return unless -f && /\.pm$/;
                    my $src = $File::Find::name;
                    (my $rel = $src) =~ s{^lib/}{};
                    $pm{$src} = File::Spec->catfile($INSTALL_BASE, $rel);
                },
                no_chdir => 1,
            }, 'lib');
        }
        
        # Also check for blib/lib (after a build)
        if (-d 'blib/lib') {
            find({
                wanted => sub {
                    return unless -f && /\.pm$/;
                    my $src = $File::Find::name;
                    (my $rel = $src) =~ s{^blib/lib/}{};
                    $pm{$src} = File::Spec->catfile($INSTALL_BASE, $rel);
                },
                no_chdir => 1,
            }, 'blib/lib');
        }
    }
    
    if (!%pm) {
        print "Warning: No .pm files found to install.\n";
        print "Expected structure: lib/Your/Module.pm\n\n";
        return PerlOnJava::MM::Installed->new($args);
    }
    
    print "\nInstalling to: $INSTALL_BASE\n\n";
    
    # Install .pm files
    my $installed = 0;
    for my $src (sort keys %pm) {
        my $dest = $pm{$src};
        my $dir = dirname($dest);
        
        if (!-d $dir) {
            make_path($dir) or warn "Failed to create $dir: $!\n";
        }
        
        print "  $src -> $dest\n";
        if (copy($src, $dest)) {
            $installed++;
        } else {
            warn "  Failed to copy: $!\n";
        }
    }
    
    # Install scripts
    if ($args->{EXE_FILES} && @{$args->{EXE_FILES}}) {
        print "\nInstalling scripts:\n";
        my $bin_dir = File::Spec->catdir($INSTALL_BASE, '..', 'bin');
        make_path($bin_dir) unless -d $bin_dir;
        
        for my $script (@{$args->{EXE_FILES}}) {
            my $dest = File::Spec->catfile($bin_dir, basename($script));
            print "  $script -> $dest\n";
            copy($script, $dest) or warn "  Failed to copy: $!\n";
        }
    }
    
    print "\n";
    print "=" x 60, "\n";
    print "Installation complete! ($installed files installed)\n";
    print "=" x 60, "\n\n";
    
    # Create a stub Makefile to satisfy CPAN.pm's check
    _create_stub_makefile($name, $version, $args);
    
    return PerlOnJava::MM::Installed->new($args);
}

sub _extract_version {
    my ($file) = @_;
    return '0' unless -f $file;
    
    open my $fh, '<', $file or return '0';
    while (<$fh>) {
        if (/\$VERSION\s*=\s*['"]?([\d._]+)/) {
            return $1;
        }
        # Also handle: our $VERSION = version->declare('v1.2.3');
        if (/\$VERSION\s*=\s*version->/) {
            if (/['"]v?([\d.]+)/) {
                return $1;
            }
        }
    }
    close $fh;
    return '0';
}

sub _create_stub_makefile {
    my ($name, $version, $args) = @_;
    
    # Create a minimal Makefile that CPAN.pm can parse
    # This allows CPAN.pm to proceed through its make/test/install workflow
    my $makefile = 'Makefile';
    
    open my $fh, '>', $makefile or do {
        warn "Note: Could not create stub Makefile: $!\n";
        return;
    };
    
    # Minimal Makefile that works with CPAN.pm
    print $fh <<"MAKEFILE";
# Stub Makefile for PerlOnJava
# This module was installed directly without 'make'

NAME = $name
VERSION = $version
PERL = $^X
INSTALLDIRS = site

# PerlOnJava installs modules directly - these are no-ops
all:
\t\@echo "PerlOnJava: Module already installed"

test:
\t\@echo "PerlOnJava: Tests skipped (module already installed)"

install:
\t\@echo "PerlOnJava: Module already installed to $INSTALL_BASE"

clean:
\t\@echo "PerlOnJava: Nothing to clean"

realclean: clean

distclean: clean

.PHONY: all test install clean realclean distclean
MAKEFILE

    close $fh;
}

sub prompt {
    my ($msg, $default) = @_;
    $default //= '';
    print "$msg [$default] ";
    my $answer = <STDIN>;
    chomp $answer if defined $answer;
    return (defined $answer && $answer ne '') ? $answer : $default;
}

# Format a value for display (used by some Makefile.PL scripts)
sub neatvalue {
    my ($val) = @_;
    return 'undef' unless defined $val;
    return "'$val'" if $val =~ /\D/;
    return $val;
}

#############################################################################
# Stub MM object for installed modules
#############################################################################
package PerlOnJava::MM::Installed;

sub new { 
    my ($class, $args) = @_;
    bless { args => $args }, $class;
}

sub flush { 1 }

# No-op methods that Makefile.PL might call
sub AUTOLOAD {
    my $self = shift;
    our $AUTOLOAD;
    # Silently ignore unknown method calls
    return;
}

sub DESTROY {}

#############################################################################
# Stub MM object for XS modules (not installed)
#############################################################################
package PerlOnJava::MM::XSStub;

sub new { 
    my ($class, $name, $xs_files, $args) = @_;
    bless { name => $name, xs => $xs_files, args => $args }, $class;
}

sub flush { 
    my $self = shift;
    print "Skipped XS module: $self->{name}\n";
    return 0;
}

sub AUTOLOAD {
    my $self = shift;
    our $AUTOLOAD;
    return;
}

sub DESTROY {}

1;

__END__

=head1 NAME

ExtUtils::MakeMaker - PerlOnJava implementation

=head1 SYNOPSIS

    # In Makefile.PL
    use ExtUtils::MakeMaker;
    
    WriteMakefile(
        NAME         => 'My::Module',
        VERSION_FROM => 'lib/My/Module.pm',
        PREREQ_PM    => { 'Some::Module' => 0 },
    );

=head1 DESCRIPTION

This is a PerlOnJava-specific implementation of ExtUtils::MakeMaker.
Instead of generating a Makefile for C compilation, it:

=over 4

=item *

For pure Perl modules: directly copies .pm files to the installation directory

=item *

For XS/C modules: prints guidance on how to port to Java

=back

=head1 ENVIRONMENT VARIABLES

=over 4

=item PERLONJAVA_LIB

Installation directory for modules. Defaults to ./lib or relative to the JAR.

=back

=head1 SEE ALSO

L<docs/guides/using-cpan-modules.md> for information on adding CPAN modules.

=cut
