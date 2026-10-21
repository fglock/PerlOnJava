use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);
use IO::Select;
use Socket qw(AF_UNIX SOCK_DGRAM pack_sockaddr_un unpack_sockaddr_un);

plan skip_all => 'Unix-domain datagram sockets are not available on Windows'
    if $^O eq 'MSWin32';

my $dir = tempdir(CLEANUP => 1);
my $server_path = "$dir/server.sock";
my $client_path = "$dir/client.sock";
socket(my $server, AF_UNIX, SOCK_DGRAM, 0)
    or die "AF_UNIX datagram socket unavailable: $!";
socket(my $client, AF_UNIX, SOCK_DGRAM, 0)
    or die "AF_UNIX datagram socket unavailable: $!";

bind($server, pack_sockaddr_un($server_path))
    or die "AF_UNIX datagram bind unavailable: $!";
bind($client, pack_sockaddr_un($client_path))
    or die "AF_UNIX datagram bind unavailable: $!";
connect($client, pack_sockaddr_un($server_path))
    or die "AF_UNIX datagram connect unavailable: $!";
pass 'bind server pathname';
pass 'bind client pathname';
pass 'connect client to server';
is unpack_sockaddr_un(getsockname($server)), $server_path,
    'getsockname reports the bound server pathname';
is unpack_sockaddr_un(getpeername($client)), $server_path,
    'getpeername reports the connected peer pathname';
is send($client, 'datagram', 0), 8, 'send one datagram';
my @readable = IO::Select->new($server)->can_read(2);
is scalar @readable, 1, 'select reports the native socket as readable';
my $buffer = '';
my $sender = recv($server, $buffer, 64, 0);
ok defined($sender), 'receive returns the sender address';
is $buffer, 'datagram', 'received datagram payload is intact';
is send($server, 'reply', 0, pack_sockaddr_un($client_path)), 5,
    'sendto reaches a bound Unix datagram peer';
my $reply = '';
my $reply_sender = recv($client, $reply, 64, 0);
ok defined($reply_sender), 'recvfrom returns sender address for sendto';
is $reply, 'reply', 'sendto payload is intact';
is unpack_sockaddr_un($reply_sender), $server_path, 'recvfrom reports sender pathname';

ok close($client), 'close client releases native descriptor';
ok close($server), 'close server releases native descriptor';
done_testing;
