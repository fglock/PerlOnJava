use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More;

our @destroyed;
our ($scalar_weak, $array_weak, $hash_weak, $unit_seen);

{
    package Issue1649::UnitcheckCaptureProbe;
    sub DESTROY { push @main::destroyed, $_[0]{label} }
}

my $scalar_probe;
my (@array_probe, %hash_probe);
BEGIN {
    $scalar_probe = bless { label => 'scalar' }, 'Issue1649::UnitcheckCaptureProbe';
    @array_probe = (bless { label => 'array' }, 'Issue1649::UnitcheckCaptureProbe');
    %hash_probe = (item => bless { label => 'hash' }, 'Issue1649::UnitcheckCaptureProbe');

    $scalar_weak = $scalar_probe;
    $array_weak = $array_probe[0];
    $hash_weak = $hash_probe{item};
    weaken($scalar_weak);
    weaken($array_weak);
    weaken($hash_weak);
}

UNITCHECK {
    $unit_seen = defined($scalar_probe)
            && @array_probe == 1
            && keys(%hash_probe) == 1 ? 1 : 0;
}

ok($unit_seen, 'UNITCHECK sees its captured scalar, array, and hash lexicals');
ok(defined($scalar_weak), 'the scalar pad retains its blessed value after UNITCHECK');
ok(defined($array_weak), 'the array pad retains its blessed value after UNITCHECK');
ok(defined($hash_weak), 'the hash pad retains its blessed value after UNITCHECK');

undef $scalar_probe;
@array_probe = ();
%hash_probe = ();
ok(!defined($scalar_weak), 'releasing the scalar pad clears its weak observer');
ok(!defined($array_weak), 'releasing the array pad clears its weak observer');
ok(!defined($hash_weak), 'releasing the hash pad clears its weak observer');
is_deeply(\@destroyed, ['scalar', 'array', 'hash'],
    'each captured blessed value is destroyed exactly once');

done_testing;
