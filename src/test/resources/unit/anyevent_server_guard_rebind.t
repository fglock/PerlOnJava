use strict;
use warnings;
use Test::More;

unless (eval { require AnyEvent; require AnyEvent::Socket; 1 }) {
    plan skip_all => 'AnyEvent is not installed';
}

plan tests => 2;
my $port;
{
    my $server = AnyEvent::Socket::tcp_server(undef, 0, sub {}, sub {
        $port = $_[2];
        return 128;
    });
    ok($server && $port, 'first AnyEvent server binds an ephemeral port');
}

my $rebound = eval {
    AnyEvent::Socket::tcp_server(undef, $port, sub {});
};
ok($rebound, 'server guard releases its port when it leaves scope')
    or diag($@ || 'second AnyEvent server was not created');

undef $rebound;
