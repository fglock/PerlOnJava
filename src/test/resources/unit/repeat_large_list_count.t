use strict;
use warnings;
use Test::More tests => 1;

my @values;
eval { @values = (1) x ~1; 1 };
like($@, qr/Out of memory/, 'oversized list repetition count fails before allocation');
