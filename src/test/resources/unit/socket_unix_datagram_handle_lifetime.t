use strict;
use warnings;
use Test::More;
use Socket qw(AF_UNIX SOCK_DGRAM sockaddr_un);
use File::Temp qw(tempdir);

plan skip_all => 'Unix-domain datagram sockets are not available on Windows'
    if $^O eq 'MSWin32';

my $dir = tempdir(CLEANUP => 1);
my $server_path = "$dir/server.sock";
my $client_path = "$dir/client.sock";

socket my $Srv, AF_UNIX, SOCK_DGRAM, 0 or die "server socket: $!";
bind $Srv, sockaddr_un($server_path) or die "server bind: $!";
socket my $Cli, AF_UNIX, SOCK_DGRAM, 0 or die "client socket: $!";
bind $Cli, sockaddr_un($client_path) or die "client bind: $!";
connect $Cli, sockaddr_un($server_path) or die "initial connect: $!";

sub read_server_datagram() {
    sysread $Srv, my $buffer, 8192 or die "server sysread: $!";
    return $buffer;
}

is send($Cli, 'first', 0), 5, 'send first datagram';
is read_server_datagram(), 'first', 'server reads first datagram';

socket my $Reconnect, AF_UNIX, SOCK_DGRAM, 0
    or die "reconnect socket: $!";
ok connect($Reconnect, sockaddr_un($server_path)),
    'server handle remains bound after sysread';
is send($Reconnect, 'second', 0), 6, 'reconnected client sends datagram';
is read_server_datagram(), 'second', 'server reads datagram after reconnect';

ok close($Reconnect), 'close reconnecting client';
ok close($Cli), 'close original client';
ok close($Srv), 'close server';
done_testing;
