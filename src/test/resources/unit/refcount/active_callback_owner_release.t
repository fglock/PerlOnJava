use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);
our ($active, $weak_callback, $weak_guard, $destroyed);
$destroyed = 0;
{ package ActiveCallGuard; sub DESTROY { ++$main::destroyed } }
sub make_callback {
    my ($throw) = @_;
    my $guard = bless { value => 1 }, 'ActiveCallGuard';
    $weak_guard = $guard;
    weaken($weak_guard);
    return sub {
        my ($depth) = @_;
        if ($depth) {
            $active->(0);
            ok(defined $weak_callback, 'outer active call still owns the callback after inner return');
            ok(defined $weak_guard, 'outer call still owns the captured guard');
        } else {
            undef $active;
            ok(defined $weak_callback, 'active invocation owns callback after the last scalar is cleared');
            ok(defined $weak_guard, 'active callback keeps its captured guard');
        }
        die "callback failed\n" if $throw;
        return $guard->{value};
    };
}
$active = make_callback(0);
$weak_callback = $active;
weaken($weak_callback);
$active->(1);
ok(!defined $weak_callback, 'final returning invocation releases its callback');
ok(!defined $weak_guard, 'final callback release drops its captured guard');
is($destroyed, 1, 'recursive dispatch destroys the captured guard exactly once');

$active = make_callback(1);
$weak_callback = $active;
weaken($weak_callback);
eval { $active->(0) };
like($@, qr/callback failed/, 'callback exception propagates');
ok(!defined $weak_callback, 'exception unwind releases its active callback owner');
ok(!defined $weak_guard, 'exception unwind releases captured data');
is($destroyed, 2, 'each captured guard is destroyed once');
done_testing();
