use strict;
use warnings;
use Test::More tests => 2;

use Compress::Raw::Zlib qw(Z_DATA_ERROR Z_OK);

is(Z_DATA_ERROR, -3, 'imported zlib constants compile as constant subs under strict subs');
is(Z_OK, 0, 'multiple imported zlib constants remain callable');
