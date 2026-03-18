package ExtUtils::MY;
use strict;
use warnings;

our $VERSION = '7.70_perlonjava';

# MY is used for user customizations in Makefile.PL
# In PerlOnJava, this is a stub since we don't generate Makefiles.

# Note: Do NOT use ExtUtils::MakeMaker here - it would create a circular dependency
# The @ISA inheritance from ExtUtils::MM is all we need
require ExtUtils::MM;
our @ISA = ('ExtUtils::MM');

# Provide stub for subclassing
sub new {
    my $class = shift;
    my $self = $class->SUPER::new(@_);
    return $self;
}

# Allow overriding methods
sub AUTOLOAD {
    my $self = shift;
    our $AUTOLOAD;
    return;
}

sub DESTROY {}

1;

__END__

=head1 NAME

ExtUtils::MY - PerlOnJava stub for MakeMaker customization

=head1 DESCRIPTION

In traditional MakeMaker, MY is a subclass for user customizations.
In PerlOnJava, this is a stub since we don't generate Makefiles.

=cut
