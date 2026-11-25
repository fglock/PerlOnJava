use strict;
use warnings;
use Test::More tests => 1;

pass('__END__ with trailing same-line text stops parsing');
__END__ DATA
This trailing payload must not be parsed as Perl source.
