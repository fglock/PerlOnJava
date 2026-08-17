use strict;
use warnings;
use Test::More tests => 2;

use feature 'unicode_strings';

my $byte = "\xDF";
is(unpack('H*', lc $byte), 'df', 'unicode_strings preserves lowercase byte behavior');
is(unpack('H*', uc $byte), '5353', 'unicode_strings applies Unicode uppercase mapping');
