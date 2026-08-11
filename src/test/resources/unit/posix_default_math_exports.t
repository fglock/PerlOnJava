use strict;
use warnings;
use Test::More tests => 4;

use POSIX;

ok(defined(&floor), 'floor is exported by default');
ok(defined(&ceil), 'ceil is exported by default');
is(floor(3.75), 3, 'default floor export is callable');
is(ceil(-3.75), -3, 'default ceil export is callable');
