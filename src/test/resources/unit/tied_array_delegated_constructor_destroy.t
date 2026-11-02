use strict;
use warnings;
use Test::More tests => 1;

{
    package Local::SaveLaterBase;
    our $destroyed = 0;

    sub TIEARRAY { shift->TIE('ARRAY' => @_) }

    sub TIE {
        my ($class, $type) = @_;
        my $self = {
            HASH   => {},
            ARRAY  => [],
            SCALAR => \do { my $scalar },
        }->{$type};
        bless $self => "${class}::${type}";
        return $self;
    }

    sub DESTROY { ++$destroyed }

    package Local::SaveLater;
    our @ISA = ('Local::SaveLaterBase');

    package Local::SaveLater::ARRAY;
    our @ISA = ('Local::SaveLater', 'Tie::StdArray');
}

{
    tie my @values, 'Local::SaveLater';
}

is($Local::SaveLaterBase::destroyed, 1,
    'a tied handler returned through a delegated constructor is destroyed');
