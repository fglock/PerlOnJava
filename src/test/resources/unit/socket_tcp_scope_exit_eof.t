use strict;
use warnings;
use Fcntl qw(F_GETFL F_SETFL O_NONBLOCK);
use IO::Socket::INET;
use Test::More;

sub make_nonblocking {
    my ($socket) = @_;
    my $flags = fcntl($socket, F_GETFL, 0);
    die "F_GETFL: $!" unless defined $flags;
    my $set = fcntl($socket, F_SETFL, $flags | O_NONBLOCK);
    die "F_SETFL: $!" unless defined $set;
}

my $listener = IO::Socket::INET->new(
    Listen    => 5,
    LocalAddr => '127.0.0.1',
    LocalPort => 0,
    Proto     => 'tcp',
    ReuseAddr => 1,
);
plan skip_all => "loopback listener unavailable: $!" unless $listener;
plan tests => 3;

my $peer = IO::Socket::INET->new(
    PeerAddr => '127.0.0.1',
    PeerPort => $listener->sockport,
    Proto    => 'tcp',
) or die "client: $!";

{
    my $server = $listener->accept or die "accept: $!";
    is(syswrite($server, 'x'), 1, 'TCP peer writes before socket scope exits');
}

close $listener or die "close listener: $!";
make_nonblocking($peer);
is(sysread($peer, my $body, 1), 1, 'client reads the final response byte');
is(sysread($peer, my $eof, 1), 0, 'client observes EOF after the TCP handle leaves scope');
