package Set::Scalar::Real;

use strict;
local $^W = 1;

use vars qw($VERSION @ISA);

$VERSION = '1.29';
@ISA = qw(Set::Scalar::Base);

use Set::Scalar::Base qw(_make_elements _binary_underload);

use overload
    '+='	=> \&_insert_overload,
    '-='	=> \&_delete_overload,
    '/='	=> \&_invert_overload;

sub insert {
    my $self = shift;

    $self->_insert( { _make_elements @_ } );

    return $self;
}

sub _insert_overload {
    my ($this, $that) = _binary_underload( \@_ );

    $that = (ref $this)->new($that) unless ref $that;

    $this->insert( $that->elements );

    return $this;
}

sub _delete {
    my $self     = shift;
    my $elements = shift;

    delete @{ $self->{'elements'} }{ keys %$elements };

    $self->_invalidate_cached;

    return $self;
}

sub delete {
    my $self     = shift;

    $self->_delete( { _make_elements @_ } );
}

sub _delete_overload {
    my ($this, $that) = _binary_underload( \@_ );

    $this->delete( $that->elements );

    return $this;
}

sub _invert {
    my $self     = shift;
    my $elements = shift;

    foreach my $element ( keys %$elements ) {
	if ( exists $self->{'elements'}->{ $element } ) {
	    delete $self->{'elements'}->{ $element };
	} else {
	    $self->{'elements'}->{ $element } = $elements->{ $element };
	}
    }

    $self->_invalidate_cached;
}

sub invert {
    my $self = shift;

    $self->_invert( { _make_elements @_ } );

    return $self;
}

sub _invert_overload {
    my ($this, $that) = _binary_underload( \@_ );

    $this->invert( $that->elements );

    return $this;
}

sub clear {
    my $self  = shift;

    die __PACKAGE__ . "::clear(): need no arguments.\n" if @_;

    $self->delete( $self->elements );

    return $self;
}

sub fill {
    my $self  = shift;

    die __PACKAGE__ . "::fill(): need no arguments.\n" if @_;

    $self->insert( $self->universe->elements );

    return $self;
}

sub DESTROY {
    my $self = shift;

    delete $self->{'null'    };
    delete $self->{'universe'};

    $self->clear;
}

=pod

=head1 NAME

Set::Scalar::Real - internal class for Set::Scalar

=head1 SYNOPSIS

B<Internal use only>.

=head1 DESCRIPTION

B<This is not the module you are looking for.>
If you want documentation see L<Set::Scalar>.

=head1 AUTHOR

Jarkko Hietaniemi <jhi@iki.fi>

=cut

1;
