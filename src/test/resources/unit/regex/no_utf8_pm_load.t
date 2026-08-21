use strict;
use warnings;
use Test::More tests => 3;

ok(!exists $INC{'utf8.pm'}, 'utf8 pragma is not reported loaded at startup');

"\0" =~ /[\001-\xFF]/i;
ok(!exists $INC{'utf8.pm'}, 'Latin-1 case folding does not load utf8 pragma');

require utf8;
ok(exists $INC{'utf8.pm'}, 'explicit require records utf8 pragma in INC');
