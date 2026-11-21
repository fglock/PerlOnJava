package Set::Scalar::Virtual;

use strict;
local $^W = 1;

use vars qw($VERSION @ISA);

$VERSION = '1.29';
@ISA = qw(Set::Scalar::Base);

use Set::Scalar::Base qw(_make_elements as_string _compare _strval);

use overload
    '""'	=> \&as_string,
    'eq'	=> \&are_equal,
    '=='	=> \&are_equal;

sub ELEMENT_SEPARATOR { " " }

sub _extend {
    my $self     = shift;
    my $elements = shift;

    $self->_insert_elements( $elements );
}

sub extend {
    my $self     = shift;

    $self->_extend( { _make_elements( @_ ) } );
}

sub compare {
    my $a = shift;
    my $b = shift;

    if (ref $a && ref $b && $a->isa(__PACKAGE__) && $b->isa(__PACKAGE__)) {
	$a = _strval($a);
	$b = _strval($b);
    }

    return _compare($a, $b);
}

sub are_equal {
    my $a = shift;
    my $b = shift;

    return $a->compare($b) eq 'equal';
}

sub clone {
    my $self     = shift;

    return $self;
}

=pod

=head1 NAME

Set::Scalar::Virtual - internal class for Set::Scalar

=head1 SYNOPSIS

B<Internal use only>.

=head1 DESCRIPTION

B<This is not the module you are looking for.>
See the L<Set::Scalar>.

=head1 AUTHOR

Jarkko Hietaniemi <jhi@iki.fi>

=cut

1;
