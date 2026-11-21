package Set::Scalar::Universe;

use strict;
local $^W = 1;

use vars qw($VERSION @ISA);

$VERSION = '1.29';
@ISA = qw(Set::Scalar::Virtual Set::Scalar::Base);

use Set::Scalar::Base qw(_make_elements);
use Set::Scalar::Virtual;
use Set::Scalar::Null;

use overload
    'neg'	=> \&_complement_overload;

my $UNIVERSE = __PACKAGE__->new;

sub SET_FORMAT        { "[%s]" }

sub universe {
    my $self = shift;

    return $UNIVERSE;
}

sub null {
    my $self = shift;

    return $self->{'null'};
}

sub enter {
    my $self = shift;

    $UNIVERSE = $self;
}

sub _new_hook {
    my $self     = shift;
    my $elements = shift;

    $self->{'universe'} = $UNIVERSE;
    $self->{'null'    } = Set::Scalar::Null->new( $self );

    $self->_extend( { _make_elements( @$elements ) } );
}

sub _complement_overload {
    my $self = shift;

    return Set::Scalar::Null->new( $self );
}

=pod

=head1 NAME

Set::Scalar::Universe - universes for set members

=head1 SYNOPSIS

B<Do not use directly.>

=head1 DESCRIPTION

There are only two guaranteed interfaces, both sort of indirect.

The first one is accessing the universe of a set:

    $set->universe

This contains the members of the universe

    $set->universe->members

of the C<$set>.

The second supported interface is displaying set universes.

    print $set->universe, "\n";

This will display the members of the set inside square brackets: [],
as opposed to sets,  which have their members shown inside
parentheses: ().

=head1 AUTHOR

Jarkko Hietaniemi <jhi@iki.fi>

=cut

1;
