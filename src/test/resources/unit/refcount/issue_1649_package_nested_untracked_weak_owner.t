use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken isweak);

{
    package PackageNestedUntrackedWeakOwner;
    our @root;
}

my $weak;
{
    my $target = {};
    @PackageNestedUntrackedWeakOwner::root = ($target);
    $weak = $target;
    weaken($weak);

    ok(isweak($weak), 'the observer is weak while the package aggregate owns the target');
    ok(defined($weak), 'a nested package owner keeps the weak referent alive');
}

ok(defined($weak), 'leaving the original lexical preserves the package-owned referent');
@PackageNestedUntrackedWeakOwner::root = ();
ok(!defined($weak), 'removing the final package owner clears the weak observer');

my $strong_after_unweaken;
my $weak_probe;
my $has_unweaken = eval {
    require Scalar::Util;
    Scalar::Util->import('unweaken');
    1;
};

SKIP: {
    skip 'Scalar::Util::unweaken is unavailable', 3 unless $has_unweaken;

    {
        my $target = {};
        @PackageNestedUntrackedWeakOwner::root = ($target);
        $strong_after_unweaken = $target;
        weaken($strong_after_unweaken);
        $weak_probe = $target;
        weaken($weak_probe);
        Scalar::Util::unweaken($strong_after_unweaken);
    }

    @PackageNestedUntrackedWeakOwner::root = ();
    ok(defined($strong_after_unweaken),
            'unweakened scalar remains a strong owner after package removal');
    ok(defined($weak_probe), 'the weak probe survives while the unweakened owner lives');
    undef($strong_after_unweaken);
    ok(!defined($weak_probe), 'dropping the unweakened owner clears the weak probe');
}

done_testing();
