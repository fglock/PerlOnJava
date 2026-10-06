use strict;
use warnings;
use Scalar::Util qw(isweak weaken);
use Test::More;

our @destroyed;

{
    package Issue1649::AggregateCaptureProbe;

    sub DESTROY {
        push @main::destroyed, 'destroyed';
    }
}

my ($weak, $closure);
{
    my @items = (bless {}, 'Issue1649::AggregateCaptureProbe');
    $weak = $items[0];
    weaken($weak);
    $closure = sub { scalar @items };
}

ok(isweak($weak), 'the probe remains a weak reference');
ok(defined $weak, 'the captured aggregate keeps its blessed element alive');
is($closure->(), 1, 'the closure retains the captured aggregate pad');

undef $closure;
ok(!defined $weak, 'releasing the closure releases the aggregate element');
is_deeply(\@destroyed, ['destroyed'], 'the element is destroyed exactly once');

done_testing;
