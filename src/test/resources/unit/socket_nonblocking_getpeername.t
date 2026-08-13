use strict;
use warnings;
use Test::More;
use Socket qw(AF_INET SOCK_STREAM SOL_SOCKET SO_REUSEADDR inet_aton
              pack_sockaddr_in unpack_sockaddr_in);
use Fcntl qw(F_GETFL F_SETFL O_NONBLOCK);

socket(my $server, AF_INET, SOCK_STREAM, 0) or die "server socket: $!";
setsockopt($server, SOL_SOCKET, SO_REUSEADDR, 1) or die "reuseaddr: $!";
bind($server, pack_sockaddr_in(0, inet_aton('127.0.0.1'))) or die "bind: $!";
listen($server, 4) or die "listen: $!";
my ($port) = unpack_sockaddr_in(getsockname($server));

my %state;
my $created_fd = do {
    socket($state{client}, AF_INET, SOCK_STREAM, 0) or die "client socket: $!";
    fileno($state{client});
};
is(fileno($state{client}), $created_fd, 'container-owned socket keeps its descriptor after creator scope exits');
my $flags = fcntl($state{client}, F_GETFL, 0);
fcntl($state{client}, F_SETFL, $flags | O_NONBLOCK) or die "nonblocking: $!";
connect($state{client}, pack_sockaddr_in($port, inet_aton('127.0.0.1')));

my $write = '';
vec($write, fileno($state{client}), 1) = 1;
ok(select(undef, $write, undef, 5) > 0, 'nonblocking connection becomes write-ready');
ok(defined getpeername($state{client}), 'getpeername confirms a write-ready nonblocking connection');

my $accepted;
ok(accept($accepted, $server), 'accepts the pending client');
$flags = fcntl($server, F_GETFL, 0);
fcntl($server, F_SETFL, $flags | O_NONBLOCK) or die "server nonblocking: $!";
my $none = accept(my $extra, $server);
ok(!defined($none), 'a second accept on a nonblocking listener returns immediately');

close $accepted;
close $state{client};
close $server;
done_testing;
