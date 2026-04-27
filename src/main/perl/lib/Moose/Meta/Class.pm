package Moose::Meta::Class;

# PerlOnJava skeleton stub for Moose::Meta::Class.
#
# Inherits from Class::MOP::Class so that
# `Class::MOP::Class->isa('Moose::Meta::Class')` and the reverse work
# the way user code expects. No real metaclass machinery.
#
# See dev/modules/moose_support.md.

use strict;
use warnings;

our $VERSION = '2.4000';

require Class::MOP::Class;
our @ISA = ('Class::MOP::Class');

sub initialize {
    my ($class, @args) = @_;
    return Class::MOP::Class->initialize(@args);
}

sub create {
    my ($class, @args) = @_;
    return Class::MOP::Class->create(@args);
}

sub create_anon_class {
    my ($class, @args) = @_;
    return Class::MOP::Class->create_anon_class(@args);
}

1;

__END__

=head1 NAME

Moose::Meta::Class - PerlOnJava skeleton stub.

=cut
