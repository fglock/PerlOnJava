package Moose::Object;

# PerlOnJava Moose::Object stub.
#
# Real Moose objects inherit from Moose::Object, which provides BUILDARGS,
# BUILD/DEMOLISH chaining, ->new, ->meta, ->does, ->DOES, etc. The Moose shim
# in Moose.pm sets up Moo as the actual constructor backend, so this module
# only needs to:
#   - exist (so $obj->isa('Moose::Object') is true)
#   - provide a polite `new` if someone calls Moose::Object->new directly
#   - provide ->meta/->does/->DOES that work in the absence of a real MOP
#
# See dev/modules/moose_support.md.

use strict;
use warnings;

our $VERSION = '2.4000';

sub new {
    my $class = shift;
    my %args  = @_ == 1 && ref $_[0] eq 'HASH' ? %{ $_[0] } : @_;
    bless { %args }, $class;
}

sub meta {
    my $self = shift;
    my $name = ref($self) || $self;
    require Moose;
    Moose::_FakeMeta->_for($name);
}

sub does {
    my ($self, $role) = @_;
    return $self->isa($role);
}

sub DOES {
    my ($self, $role) = @_;
    return $self->isa($role);
}

sub BUILDARGS {
    my $class = shift;
    return @_ == 1 && ref $_[0] eq 'HASH' ? { %{ $_[0] } } : { @_ };
}

sub BUILDALL    { }
sub DEMOLISHALL { }

1;

__END__

=head1 NAME

Moose::Object - PerlOnJava Moose::Object compatibility stub

=head1 DESCRIPTION

Marker base class used by the Moose shim. See L<Moose> and
C<dev/modules/moose_support.md>.

=cut
