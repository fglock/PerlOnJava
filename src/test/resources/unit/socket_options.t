#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;
use Socket qw(
    AF_INET AF_UNIX PF_UNSPEC SOCK_STREAM
    SOL_SOCKET SO_ACCEPTCONN SO_REUSEADDR SO_TYPE
    INADDR_LOOPBACK IN6ADDR_ANY IN6ADDR_LOOPBACK
    sockaddr_in
);

is length(IN6ADDR_ANY), 16, 'IN6ADDR_ANY is a 16-byte packed address';
is IN6ADDR_LOOPBACK, ("\0" x 15) . "\1", 'IN6ADDR_LOOPBACK is packed ::1';

SKIP: {
    socketpair(my $left, my $right, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or skip "socketpair unavailable: $!", 2;

    my $opt = getsockopt($left, SOL_SOCKET, SO_TYPE);
    ok defined($opt), 'getsockopt(SO_TYPE) returns a value';
    is unpack("i", $opt), SOCK_STREAM, 'SO_TYPE reports SOCK_STREAM';
}

SKIP: {
    socket(my $server, AF_INET, SOCK_STREAM, 0)
        or skip "socket unavailable: $!", 2;
    setsockopt($server, SOL_SOCKET, SO_REUSEADDR, pack("i", 1));
    bind($server, sockaddr_in(0, INADDR_LOOPBACK))
        or skip "bind unavailable: $!", 2;
    listen($server, 1)
        or skip "listen unavailable: $!", 2;

    my $opt = getsockopt($server, SOL_SOCKET, SO_ACCEPTCONN);
    ok defined($opt), 'getsockopt(SO_ACCEPTCONN) returns a value';
    is unpack("i", $opt), 1, 'SO_ACCEPTCONN reports a listening socket';
}

done_testing;
