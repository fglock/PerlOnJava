use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken isweak);

our (@roots, @unrelated, $destroyed, $weak);
$destroyed = 0;

{
    package Issue1649::DeferredOwnerNormalStore;
    sub DESTROY { ++$main::destroyed }
}

@unrelated = map { { nested => [$_, { leaf => $_ }] } } 1 .. 256;
{
    my $value = bless {}, 'Issue1649::DeferredOwnerNormalStore';
    $weak = $value;
    weaken($weak);
    $roots[0] = [$value];
}

ok(defined($weak) && isweak($weak),
    'a nested package-rooted array retains the weakly observed value');
is($destroyed, 0, 'the rooted value is not destroyed while its package owner lives');
is(scalar @unrelated, 256, 'unrelated live aggregate roots remain present');

$roots[0] = undef;
ok(!defined($weak), 'removing the last package path clears the weak observer');
is($destroyed, 1, 'the nested value is destroyed exactly once after its final owner leaves');

done_testing();
