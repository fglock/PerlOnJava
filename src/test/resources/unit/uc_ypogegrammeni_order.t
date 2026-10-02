use strict;
use warnings;
use utf8;
use feature 'unicode_strings';
use Test::More;

is(
    uc("\x{3B1}\x{345}\x{301}"),
    "\x{391}\x{301}\x{399}",
    'uppercase moves ypogegrammeni after following combining marks',
);

done_testing();
