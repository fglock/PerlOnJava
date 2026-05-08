use strict;
use warnings;
use Test::More;

{
    package MapReturnDestroy;
    our $counter = 0;
    sub new {
        my $class = shift;
        my $self = bless {}, $class;
        $counter++;
        return $self;
    }
    sub DESTROY {
        $counter-- if $counter > 0;
    }
}

my @objs = map { MapReturnDestroy->new } 1 .. 3;
is($MapReturnDestroy::counter, 3, "map-returned objects survive until caller captures list");

@objs = ();
is($MapReturnDestroy::counter, 0, "map-returned objects are destroyed when array is cleared");

done_testing;
