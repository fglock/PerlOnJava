use strict;
use warnings;
use Test::More tests => 1;

CORE::dump if 0;
pass('guarded obsolete CORE::dump compiles');
