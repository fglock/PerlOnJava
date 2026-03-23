package ExtUtils::MM;
use strict;
use warnings;

our $VERSION = '7.78_perlonjava';
our @ISA;

# MM is a compatibility shim that some modules expect.
# In traditional MakeMaker, MM is the platform-specific Makefile generator.
# In PerlOnJava, we use MM_PerlOnJava which handles the JVM-specific details.

# Load platform-specific module and set up inheritance
BEGIN {
    # Detect PerlOnJava environment - works on both Unix and Windows
    # Check for PERLONJAVA_JAR env var or jperl in the interpreter path
    my $Is_PerlOnJava = exists $ENV{PERLONJAVA_JAR} 
                     || $^X =~ /jperl(?:\.bat|\.cmd)?$/i
                     || exists $ENV{PERLONJAVA_LIB};
    
    if ($Is_PerlOnJava) {
        require ExtUtils::MM_PerlOnJava;
        push @ISA, 'ExtUtils::MM_PerlOnJava';
    } elsif ($^O eq 'MSWin32') {
        require ExtUtils::MM_Win32;
        push @ISA, 'ExtUtils::MM_Win32';
    } else {
        require ExtUtils::MM_Unix;
        push @ISA, 'ExtUtils::MM_Unix';
    }
}

# Note: Do NOT use ExtUtils::MakeMaker here - it would create a circular dependency
# ExtUtils::MakeMaker already requires ExtUtils::MM

# Convenient alias - allows MM->method() syntax
{
    package MM;
    our @ISA = qw(ExtUtils::MM);
    sub DESTROY {}
}

# Provide any methods that Makefile.PL might call on MM
sub new {
    my $class = shift;
    my %args = @_;
    bless \%args, $class;
}

# These methods are sometimes called by complex Makefile.PL scripts
sub parse_args { }
sub init_dirscan { }
sub init_others { }
sub init_main { }
sub init_PM { }
sub init_INST { }
sub init_INSTALL { }
sub init_xs { }

# Return empty hash for various attribute accessors
sub AUTOLOAD {
    my $self = shift;
    our $AUTOLOAD;
    return;
}

sub DESTROY {}

1;

__END__

=head1 NAME

ExtUtils::MM - PerlOnJava stub

=head1 DESCRIPTION

This is a compatibility stub for modules that reference ExtUtils::MM directly.
In PerlOnJava, the MakeMaker functionality is handled by ExtUtils::MakeMaker.

On Unix-like systems, inherits from ExtUtils::MM_Unix.
On Windows, inherits from ExtUtils::MM_Win32.

=cut
