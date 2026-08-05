use strict;
use warnings;

use Test::More tests => 2;
use Socket qw(NI_NAMEREQD);

is(NI_NAMEREQD, 4, 'NI_NAMEREQD has the standard flag value');
ok(defined &NI_NAMEREQD, 'NI_NAMEREQD is exportable from Socket');
