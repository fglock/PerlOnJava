use strict;
use warnings;
use Test::More;
use POSIX qw(cbrt isfinite isinf isnan isnormal signbit HUGE_VAL);

cmp_ok(abs(cbrt(-8) + 2), '<', 1e-12, 'cbrt handles negative numbers');
ok(isfinite(42), 'finite number is finite');
ok(!isfinite(HUGE_VAL), 'infinity is not finite');
ok(isinf(HUGE_VAL), 'detects infinity');
ok(isnan(HUGE_VAL - HUGE_VAL), 'detects NaN');
ok(isnormal(1), 'detects a normal number');
ok(!isnormal(0), 'zero is not normal');
ok(signbit(-1), 'detects a negative sign bit');
ok(!signbit(1), 'positive number has no sign bit');

done_testing;
