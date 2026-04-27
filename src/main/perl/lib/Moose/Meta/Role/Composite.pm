package Moose::Meta::Role::Composite;

# PerlOnJava skeleton stub for Moose::Meta::Role::Composite. Used when
# multiple roles are composed into a single role at apply-time.
# Returns a Moose::Meta::Role-shaped object.

use strict;
use warnings;

our $VERSION = '2.4000';

require Moose::Meta::Role;
our @ISA = ('Moose::Meta::Role');

sub new {
    my ($class, %opts) = @_;
    my @roles = @{ $opts{roles} || [] };
    my $name  = $opts{name} || join('|', map { ref $_ ? $_->name : $_ } @roles);
    return bless {
        name        => $name,
        attributes  => {},
        attr_order  => [],
        roles       => [@roles],
    }, $class;
}

1;
__END__
=head1 NAME
Moose::Meta::Role::Composite - PerlOnJava skeleton stub.
=cut
