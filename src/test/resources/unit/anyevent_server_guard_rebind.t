use strict;
use warnings;
use IO::Socket::INET;
use Test::More;

unless (eval { require AnyEvent; require AnyEvent::Socket; 1 }) {
    plan skip_all => 'AnyEvent is not installed';
}

plan tests => 2;

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
{
    my $server = AnyEvent::Socket::tcp_server(undef, $port, sub {});
    ok($server, 'first AnyEvent server binds the port');
}

my $rebound = eval {
    AnyEvent::Socket::tcp_server(undef, $port, sub {});
};
ok($rebound, 'server guard releases its port when it leaves scope')
    or diag($@ || 'second AnyEvent server was not created');

undef $rebound;
