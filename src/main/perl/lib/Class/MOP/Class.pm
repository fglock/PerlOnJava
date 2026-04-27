package Class::MOP::Class;

# PerlOnJava skeleton stub for Class::MOP::Class.
#
# Under the Moose-as-Moo shim there is no real Class::MOP::Class. This
# module exists so that `require Class::MOP::Class` and
# `Class::MOP::Class->isa(...)` checks compile, and so that calls like
# `Class::MOP::Class->initialize($name)` return the same Moose::_FakeMeta
# the rest of the shim already hands out.
#
# See dev/modules/moose_support.md.

use strict;
use warnings;

our $VERSION = '2.4000';

require Moose;  # for Moose::_FakeMeta

sub initialize {
    my ($class, $for, %opts) = @_;
    my $name = ref($for) || $for;
    return Moose::_FakeMeta->_for($name);
}

sub create {
    my ($class, $name, %opts) = @_;
    my $meta = Moose::_FakeMeta->_for($name);
    if (my $superclasses = delete $opts{superclasses}) {
        no strict 'refs';
        @{"${name}::ISA"} = @$superclasses;
    }
    return $meta;
}

sub create_anon_class {
    my ($class, %opts) = @_;
    my $name = "Class::MOP::Class::__ANON__::SERIAL::" . _next_anon_id();
    return $class->create($name, %opts);
}

# Class::MOP::Class itself can be introspected — return a metaclass for it.
sub meta {
    my $self = shift;
    require Moose;
    my $name = ref($self) || $self;
    return Moose::_FakeMeta->_for($name);
}

{
    my $next = 0;
    sub _next_anon_id { ++$next }
}

1;

__END__

=head1 NAME

Class::MOP::Class - PerlOnJava skeleton stub.

=head1 DESCRIPTION

Returns C<Moose::_FakeMeta> instances from C<initialize> / C<create>; no
real metaclass machinery.

=cut
