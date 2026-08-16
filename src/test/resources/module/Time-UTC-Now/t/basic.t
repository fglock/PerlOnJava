use strict;
use warnings;
use Test::More;

use Time::UTC::Now qw(
    now_utc_rat now_utc_sna now_utc_flt now_utc_dec
    utc_day_to_mjdn utc_day_to_cjdn
);

is utc_day_to_mjdn(0), 36204, 'TAI epoch converts to MJDN';
is utc_day_to_cjdn(0), 2436205, 'TAI epoch converts to CJDN';

my @rat = now_utc_rat();
is scalar(@rat), 3, 'rational form has three values';
isa_ok $rat[0], 'Math::BigRat';
isa_ok $rat[1], 'Math::BigRat';
ok $rat[0]->is_int, 'rational day is integral';
ok $rat[1] >= 0 && $rat[1] < 86401, 'rational seconds are within a UTC day';

my @sna = now_utc_sna();
is scalar(@sna), 3, 'seconds/nanoseconds form has three values';
like $sna[0], qr/\A-?[0-9]+\z/, 'day number is integral';
is ref($sna[1]), 'ARRAY', 'time of day is an array';
is scalar(@{$sna[1]}), 3, 'time of day has seconds, nanoseconds, attoseconds';
ok $sna[1][0] >= 0 && $sna[1][0] < 86401, 'seconds are within a UTC day';
ok $sna[1][1] >= 0 && $sna[1][1] < 1_000_000_000, 'nanoseconds are normalized';
is $sna[1][2], 0, 'attoseconds are zero at nanosecond resolution';

my @flt = now_utc_flt();
is scalar(@flt), 3, 'floating form has three values';
ok $flt[1] >= 0 && $flt[1] < 86401, 'floating seconds are within a UTC day';

my @dec = now_utc_dec();
is scalar(@dec), 3, 'decimal form has three values';
like $dec[1], qr/\A(?:0|[1-9][0-9]*)(?:\.[0-9]*[1-9])?\z/,
    'decimal seconds are canonical';

if (defined $dec[2]) {
    my @accurate = eval { now_utc_dec(1) };
    ok !$@ && @accurate == 3, 'accuracy demand succeeds when a bound exists';
} else {
    eval { now_utc_dec(1) };
    like $@, qr/can't find time accurately/, 'accuracy demand rejects an unbounded clock';
}

done_testing;
