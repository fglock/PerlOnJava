use strict;
use warnings;
use Test::More;
use Socket qw(AF_UNIX SOCK_STREAM);

socketpair(my $left, my $right, AF_UNIX, SOCK_STREAM, 0)
    or plan skip_all => "socketpair unavailable: $!";

ok(shutdown($left, 1), 'shutdown write side of a socket');
ok(close($left), 'close shutdown socket');
ok(close($right), 'close peer socket');

done_testing;
