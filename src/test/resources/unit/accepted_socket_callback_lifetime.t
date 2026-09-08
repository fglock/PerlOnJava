use strict;
use warnings;
use IO::Socket::INET;
use Test::More;

my $listener = IO::Socket::INET->new(
    LocalAddr => '127.0.0.1',
    LocalPort => 0,
    Listen    => 1,
    ReuseAddr => 1,
);

plan skip_all => "loopback listener unavailable: $!" unless $listener;
plan tests => 3;

my $client = IO::Socket::INET->new(
    PeerAddr => '127.0.0.1',
    PeerPort => $listener->sockport,
) or die "client connect: $!";

accept(my $accepted, $listener) or die "accept: $!";

my $callback_socket;
sub invoke_callback {
    my ($callback, $socket) = @_;
    $callback->($socket);
}

invoke_callback(sub { $callback_socket = $_[0] }, $accepted);
undef $accepted;

ok defined fileno($callback_socket),
    'accepted socket remains open after callback argument assignment';
is length(getpeername($callback_socket)), 16,
    'accepted socket retains its IPv4 peer address';
is length($client->sockname), 16,
    'connected client remains valid';
