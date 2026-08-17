use strict;
use warnings;
use Test::More tests => 1;
use bigint;

is(2 ** 64 + 1, 18446744073709551617, 'bigint preserves integer precision');
