use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken refaddr);
our ($weak_callback, $weak_guard, $destroyed);
$destroyed = 0;
{ package SelfCaptureGuard; sub DESTROY { ++$main::destroyed } }
sub install_cycle {
    my $guard = bless { value => 1 }, 'SelfCaptureGuard';
    my $cycle;
    $cycle = sub {
        if ($_[0]) {
            undef $cycle;
            return $guard->{value};
        }
        return $cycle;
    };
    $weak_callback = $cycle;
    weaken($weak_callback);
    $weak_guard = $guard;
    weaken($weak_guard);
    return 1;
}
install_cycle();
ok(defined $weak_callback, 'a real self-capture cycle retains its callback after lexical exit');
SKIP: {
    skip 'callback cycle was already lost', 7 unless defined $weak_callback;
    ok(defined $weak_guard, 'the self-capture cycle owns its captured guard');
    my $strong = $weak_callback->(0);
    is(refaddr($strong), refaddr($weak_callback), 'cycle callback returns its own CV');
    is($strong->(1), 1, 'cycle can be broken while its callback is running');
    ok(defined $weak_callback, 'external holder preserves callback after cycle break');
    ok(defined $weak_guard, 'external callback holder preserves captured data');
    undef $strong;
    ok(!defined $weak_callback, 'final external owner releases the broken cycle');
    ok(!defined $weak_guard, 'broken cycle releases its captured guard');
}
is($destroyed, 1, 'breaking the self-capture cycle destroys its guard exactly once');
done_testing();
