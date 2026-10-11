use strict;
use warnings;
use IO::Socket::INET;
use Test::More tests => 3;

sub temporary_listener_port {
    my $probe = IO::Socket::INET->new(
        LocalAddr => '127.0.0.1',
        LocalPort => 0,
        Listen => 5,
        ReuseAddr => 1,
        Proto => 'tcp',
    ) or die "cannot allocate loopback port: $!";
    return $probe->sockport;
}

my $port = temporary_listener_port();
my $rebound = IO::Socket::INET->new(
    LocalAddr => '127.0.0.1',
    LocalPort => $port,
    Listen => 5,
    ReuseAddr => 1,
    Proto => 'tcp',
);
ok($rebound, 'a temporary listener closes when its lexical owner leaves scope');

SKIP: {
    skip 'AnyEvent is not installed', 2
        unless eval { require AnyEvent; require AnyEvent::Socket; 1 };

    close($rebound) if $rebound;
    my $cv = AnyEvent->condvar;
    my $accepted;
    my $server = AnyEvent::Socket::tcp_server(undef, $port, sub {
        $accepted = $_[0];
        $cv->send;
    });
    my $client = IO::Socket::INET->new(
        PeerAddr => '127.0.0.1',
        PeerPort => $port,
        Proto => 'tcp',
    );
    ok($client, 'client connects to the AnyEvent listener');

    my $timeout = AnyEvent->timer(after => 2, cb => sub { $cv->send });
    $cv->recv;
    ok($accepted, 'AnyEvent dispatches the accept watcher after port probing');

    close($client) if $client;
    close($accepted) if $accepted;
    undef $server;
    undef $timeout;
}
