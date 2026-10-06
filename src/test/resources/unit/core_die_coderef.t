use strict;
use warnings;
use Test::More tests => 1;

my $die = \&CORE::die;
eval { $die->('code reference failure') };
like($@, qr/^code reference failure at .* line \d+\.\n$/,
    'CORE::die code reference preserves the caller location');
