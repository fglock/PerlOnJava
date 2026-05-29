use strict;
use warnings;
use Test::More tests => 5;

BEGIN {
    *CORE::GLOBAL::localtime = sub (;$) {
        return wantarray ? (1, 2, 3, 4, 5, 106, 0, 0, 1) : 'mock localtime';
    };
    *CORE::GLOBAL::gmtime = sub (;$) {
        return wantarray ? (6, 7, 8, 9, 10, 111, 0, 0, 0) : 'mock gmtime';
    };
}

is scalar(localtime), 'mock localtime',
    'CORE::GLOBAL::localtime overrides bare localtime';
is_deeply [ localtime(3) ], [ 1, 2, 3, 4, 5, 106, 0, 0, 1 ],
    'CORE::GLOBAL::localtime receives explicit arguments';

is scalar(gmtime), 'mock gmtime',
    'CORE::GLOBAL::gmtime overrides bare gmtime';
is_deeply [ gmtime(3) ], [ 6, 7, 8, 9, 10, 111, 0, 0, 0 ],
    'CORE::GLOBAL::gmtime receives explicit arguments';

like scalar(CORE::gmtime(0)), qr/\AThu Jan\s+1 00:00:00 1970\z/,
    'CORE::gmtime bypasses CORE::GLOBAL override';
