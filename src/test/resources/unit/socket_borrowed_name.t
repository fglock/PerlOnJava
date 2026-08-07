use strict;
use warnings;
use Test::More;
use IO::Socket::INET;

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
my $peer = accept(my $accepted, $borrowed);
ok($peer, 'accept recognizes a borrowed listening socket');
is(getpeername($accepted), getsockname($client),
    'accepted socket reports the connected peer');

done_testing;
