use strict;
use warnings;
use Test::More tests => 4;

ok('K' !~ /^([^^]+)\^(.+)/, 'negated caret class does not match caret-free text');
ok('m^2' =~ /^([^^]+)\^(.+)/, 'negated caret class stops at a caret');
is($1, 'm', 'capture before caret is preserved');
is($2, '2', 'capture after caret is preserved');
