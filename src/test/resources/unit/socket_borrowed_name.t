use strict;
use warnings;
use Test::More;
use IO::Socket::INET;
use IO::Poll qw(POLLIN);

my $socket = IO::Socket::INET->new(
    LocalAddr => '127.0.0.1',
    LocalPort => 0,
    Proto     => 'tcp',
    Listen    => 8,
    ReuseAddr => 1,
) or die "socket: $!";

my $fd = fileno($socket);
my $borrowed;
ok(open($borrowed, '+<&=', $fd),
    'fdopen borrowed the socket at its existing descriptor');
is(fileno($borrowed), $fd, 'borrowed socket retains its descriptor');
is(getsockname($borrowed), getsockname($socket),
    'getsockname recognizes a borrowed socket handle');

my $client = IO::Socket::INET->new(
    PeerAddr => '127.0.0.1',
    PeerPort => $socket->sockport,
    Proto    => 'tcp',
) or die "client: $!";
my $poll = IO::Poll->new;
$poll->mask($borrowed, POLLIN);
cmp_ok($poll->poll(0), '>=', 1,
    'poll reports the queued connection on a borrowed listener');
my $peer = accept(my $accepted, $borrowed);
ok($peer, 'accept recognizes a borrowed listening socket');
is(getpeername($accepted), getsockname($client),
    'accepted socket reports the connected peer');
is($poll->poll(0.05), 0,
    'poll clears borrowed-listener readiness after accept');

done_testing;
