package ExtUtils::MM;
use strict;
use warnings;

our $VERSION = '7.70_perlonjava';

# MM is a compatibility shim that some modules expect.
# In traditional MakeMaker, MM is the platform-specific Makefile generator.
# In PerlOnJava, we don't generate Makefiles, so this is a stub.

use ExtUtils::MakeMaker;

# Inherit from the installed module stub
our @ISA = ('PerlOnJava::MM::Installed');

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

=cut
