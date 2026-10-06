use strict;
use warnings;
use Scalar::Util qw(isweak weaken);
use Test::More;

our @destroyed;

{
    package Issue1649::AggregateCaptureProbe;

    sub DESTROY {
        push @main::destroyed, $_[0]{label};
    }
}

my ($weak, $closure);
{
    my @items = (bless { label => 'array' }, 'Issue1649::AggregateCaptureProbe');
    $weak = $items[0];
    weaken($weak);
    $closure = sub { scalar @items };
}

ok(isweak($weak), 'the probe remains a weak reference');
ok(defined $weak, 'the captured aggregate keeps its blessed element alive');
is($closure->(), 1, 'the closure retains the captured aggregate pad');

undef $closure;
ok(!defined $weak, 'releasing the closure releases the aggregate element');
is_deeply(\@destroyed, ['array'], 'the array element is destroyed exactly once');

my ($hash_weak, $hash_closure);
{
    my %items = (item => bless { label => 'hash' }, 'Issue1649::AggregateCaptureProbe');
    $hash_weak = $items{item};
    weaken($hash_weak);
    $hash_closure = sub { scalar keys %items };
}

ok(defined $hash_weak, 'the captured hash keeps its blessed value alive');
is($hash_closure->(), 1, 'the closure retains the captured hash pad');

undef $hash_closure;
ok(!defined $hash_weak, 'releasing the closure releases the hash value');
is_deeply(\@destroyed, ['array', 'hash'], 'both aggregate values are destroyed exactly once');

done_testing;
