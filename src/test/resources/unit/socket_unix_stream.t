#!/usr/bin/env perl
use strict;
use warnings;

use File::Temp qw(tempdir);
use Socket qw(AF_UNIX SOCK_STREAM pack_sockaddr_un unpack_sockaddr_un);
use Test::More;

plan skip_all => 'Unix-domain sockets are unavailable on Windows'
    if $^O eq 'MSWin32';

my $dir = tempdir(CLEANUP => 1);
my $path = "$dir/server.sock";

socket(my $server, AF_UNIX, SOCK_STREAM, 0)
    or plan skip_all => "Unix-domain sockets unavailable: $!";
bind($server, pack_sockaddr_un($path))
    or plan skip_all => "Unix-domain socket bind unavailable: $!";
listen($server, 4) or die "listen: $!";

ok -S $path, 'bind creates a Unix-domain socket file';
is unpack_sockaddr_un(getsockname($server)), $path,
    'getsockname returns the bound Unix-domain path';

socket(my $client, AF_UNIX, SOCK_STREAM, 0) or die "client socket: $!";
connect($client, pack_sockaddr_un($path)) or die "connect: $!";
accept(my $accepted, $server) or die "accept: $!";

is syswrite($client, 'ping'), 4, 'client writes through Unix-domain socket';
my $buffer = '';
is sysread($accepted, $buffer, 4), 4, 'server reads through Unix-domain socket';
is $buffer, 'ping', 'Unix-domain socket preserves payload';

close $accepted;
close $client;
close $server;
unlink $path;

done_testing;
