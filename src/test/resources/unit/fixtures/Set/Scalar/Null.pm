package Set::Scalar::Null;

use strict;
local $^W = 1;

use vars qw($VERSION @ISA);

$VERSION = '1.29';
@ISA = qw(Set::Scalar::Base Set::Scalar::Virtual);
use Set::Scalar::Virtual;
use Set::Scalar::Base;

use overload
    'neg'	=> \&_complement_overload;

sub SET_FORMAT        { "(%s)" }

sub _new_hook {
    my $self     = shift;
    my $universe = $_[0]->[0];

    $self->universe( $universe );
}

sub universe {
    my $self = shift;

    $self->{'universe'} = shift if @_;

    return $self->{'universe'};
}

sub elements {
    return ();
}

sub size {
    return 0;
}

sub _complement_overload {
    my $self = shift;

    return Set::Scalar->new( $self->universe->elements );
}

=pod

=head1 NAME

Set::Scalar::Null - internal class for Set::Scalar

=head1 SYNOPSIS

B<Internal use only>.

=head1 DESCRIPTION

B<This is not the module you are looking for.>
If you want documentation see L<Set::Scalar>.

=head1 AUTHOR

Jarkko Hietaniemi <jhi@iki.fi>

=cut

1;
