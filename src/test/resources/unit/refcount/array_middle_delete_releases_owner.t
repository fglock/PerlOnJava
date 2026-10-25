use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

{
    package ArrayMiddleDeleteProbe;
    our $DESTROYED = 0;
    sub new { bless {}, shift }
    sub DESTROY { ++$DESTROYED }
}

my $weak;
my $deleted;
{
    my @values = (ArrayMiddleDeleteProbe->new,
                  ArrayMiddleDeleteProbe->new,
                  ArrayMiddleDeleteProbe->new);
    $weak = $values[1];
    weaken($weak);

    $deleted = delete $values[1];
    is($ArrayMiddleDeleteProbe::DESTROYED, 0,
       'deleted value stays alive while delete result is held');
    is(ref($weak), 'ArrayMiddleDeleteProbe',
       'weak reference observes the held delete result');

    undef $deleted;
    is($ArrayMiddleDeleteProbe::DESTROYED, 1,
       'middle element owner is released after delete result is dropped');
    ok(!defined($weak), 'weak reference clears when deleted value is destroyed');
}

is($ArrayMiddleDeleteProbe::DESTROYED, 3,
   'remaining array elements are destroyed at scope exit');

done_testing;
