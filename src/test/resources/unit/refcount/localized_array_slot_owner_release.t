use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

{
    package LocalizedArraySlotOwner;
    our $destroyed = 0;

    sub DESTROY { ++$destroyed }
}

my $weak;
{
    my @values;
    {
        local $values[2];
        my $value = bless {}, 'LocalizedArraySlotOwner';
        $weak = $value;
        weaken($weak);
        $values[2] = $value;
        ok(defined($weak), 'localized array slot owns its assigned reference');
    }

    ok(!defined($weak), 'unwinding a new localized array slot releases its reference');
}

is($LocalizedArraySlotOwner::destroyed, 1,
   'the localized slot dispatches DESTROY exactly once');

done_testing();
