use strict;
use warnings;
use Test::More tests => 1;
use bignum;

ok((1 / 3) > 0 && (1 / 3) < 1, 'bignum supports arbitrary-precision division');
