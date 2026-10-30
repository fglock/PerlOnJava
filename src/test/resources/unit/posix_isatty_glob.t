use strict;
use warnings;
use POSIX ();
use Test::More;

is(POSIX::isatty(*STDIN) ? 1 : 0, -t STDIN ? 1 : 0,
    'POSIX::isatty accepts the STDIN glob');
is(POSIX::isatty(*STDOUT) ? 1 : 0, -t STDOUT ? 1 : 0,
    'POSIX::isatty accepts the STDOUT glob');

done_testing;
