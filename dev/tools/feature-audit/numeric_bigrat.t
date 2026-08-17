use strict;
use warnings;
use Test::More tests => 1;
use bigrat;

is(1 / 3 + 1 / 3 + 1 / 3, 1, 'bigrat preserves rational arithmetic');
