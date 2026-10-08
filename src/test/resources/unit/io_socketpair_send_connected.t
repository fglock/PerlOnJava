use strict;
use warnings;
use Test::More;
use IO::Socket::UNIX;
use Socket qw(PF_UNIX SOCK_STREAM);

my ($left, $right) = IO::Socket::UNIX->socketpair(PF_UNIX, SOCK_STREAM, 0);
plan skip_all => "UNIX socketpair unavailable: $!" unless $left && $right;

is($left->send('payload'), 7, 'send recognizes a connected socketpair');
my $received = '';
is($right->sysread($received, 7), 7, 'socketpair peer reads the sent bytes');
is($received, 'payload', 'socketpair peer receives sent bytes');

done_testing;
