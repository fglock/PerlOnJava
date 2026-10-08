use strict;
use warnings;
use Test::More;
use IO::Socket::UNIX;
use Socket qw(PF_UNIX SOCK_STREAM);
use Fcntl qw(F_GETFL O_NONBLOCK);

my ($left, $right) = IO::Socket::UNIX->socketpair(PF_UNIX, SOCK_STREAM, 0);
plan skip_all => "UNIX socketpair unavailable: $!" unless $left && $right;

is($left->blocking(0), 1, 'blocking returns the previous socket mode');
is($left->blocking(), 0, 'socketpair switches to nonblocking mode');
SKIP: {
    skip 'Windows socketpair uses a Java loopback channel, not a native descriptor', 1
        if $^O eq 'MSWin32';
    ok(fcntl($left, F_GETFL, 0) & O_NONBLOCK, 'nonblocking mode reaches the native descriptor');
}

is($left->send('payload'), 7, 'send recognizes a connected socketpair');
my $received = '';
is($right->sysread($received, 7), 7, 'socketpair peer reads the sent bytes');
is($received, 'payload', 'socketpair peer receives sent bytes');

done_testing;
