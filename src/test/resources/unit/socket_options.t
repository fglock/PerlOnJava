#!/usr/bin/env perl
use strict;
use warnings;

use Errno qw(EAGAIN EWOULDBLOCK);
use IO::Handle;
use IO::Poll qw(POLLERR POLLIN);
use Test::More;
use Socket qw(
    AF_INET AF_UNIX PF_UNSPEC SOCK_DGRAM SOCK_STREAM
    SOL_SOCKET SO_ACCEPTCONN SO_REUSEADDR SO_TYPE
    INADDR_LOOPBACK IN6ADDR_ANY IN6ADDR_LOOPBACK
    sockaddr_in unpack_sockaddr_in
);

is length(IN6ADDR_ANY), 16, 'IN6ADDR_ANY is a 16-byte packed address';
is IN6ADDR_LOOPBACK, ("\0" x 15) . "\1", 'IN6ADDR_LOOPBACK is packed ::1';

SKIP: {
    socketpair(my $left, my $right, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
        or skip "socketpair unavailable: $!", 2;

    my $opt = getsockopt($left, SOL_SOCKET, SO_TYPE);
    ok defined($opt), 'getsockopt(SO_TYPE) returns a value';
    is unpack("i", $opt), SOCK_STREAM, 'SO_TYPE reports SOCK_STREAM';

    syswrite($right, "ready\n");
    is scalar(<$left>), "ready\n", 'readline works on socketpair handles';
}

SKIP: {
    socketpair(my $left, my $right, AF_INET, SOCK_DGRAM, PF_UNSPEC)
        or skip "inet dgram socketpair unavailable: $!", 6;

    my @right_addr = unpack_sockaddr_in(getsockname($right));
    send($right, "packet", 0);
    my $buf = "";
    my $sender = recv($left, $buf, 1024, 0);
    is $buf, "packet", 'inet dgram socketpair receives payload';
    is_deeply [ unpack_sockaddr_in($sender) ], \@right_addr, 'inet dgram socketpair reports sender address';

    syswrite($right, "sys");
    my $sysbuf = "";
    sysread($left, $sysbuf, 1024);
    is $sysbuf, "sys", 'inet dgram socketpair supports sysread/syswrite';
    syswrite($left, "back");
    $sysbuf = "";
    sysread($right, $sysbuf, 1024);
    is $sysbuf, "back", 'inet dgram socketpair sysread/syswrite is bidirectional';

    $left->blocking(0);
    $! = 0;
    my $empty = recv($left, $buf, 1024, 0);
    ok !defined($empty), 'nonblocking dgram recv returns undef when empty';
    ok $! == EAGAIN || $! == EWOULDBLOCK, 'nonblocking dgram recv reports EAGAIN';
}

SKIP: {
    socketpair(my $left, my $right, AF_INET, SOCK_DGRAM, PF_UNSPEC)
        or skip "inet dgram socketpair unavailable: $!", 4;

    $_->blocking(0) for $left, $right;
    close($right);

    ok syswrite($left, "Boo!"), 'syswrite to closed dgram peer reports sent bytes';

    my $rvec = "";
    vec($rvec, fileno($left), 1) = 1;
    my $ready = select($rvec, undef, undef, 0.1);
    ok $ready >= 1, 'select reports closed dgram peer as ready';
    ok vec($rvec, fileno($left), 1), 'select keeps closed dgram peer in read vector';

    my $poll = IO::Poll->new;
    $poll->mask($left => POLLIN);
    $ready = $poll->poll(0.1);
    ok $ready >= 1 && ($poll->events($left) & (POLLIN | POLLERR)), 'poll reports closed dgram peer as ready';
}

SKIP: {
    socket(my $udp, AF_INET, SOCK_DGRAM, 0)
        or skip "inet dgram socket unavailable: $!", 3;
    socket(my $peer, AF_INET, SOCK_DGRAM, 0)
        or skip "inet dgram peer unavailable: $!", 3;
    bind($peer, sockaddr_in(0, INADDR_LOOPBACK))
        or skip "inet dgram peer bind unavailable: $!", 3;

    my @peer_addr = unpack_sockaddr_in(getsockname($peer));
    connect($udp, getsockname($peer))
        or skip "inet dgram connect unavailable: $!", 3;

    is_deeply [ unpack_sockaddr_in(getpeername($udp)) ], \@peer_addr, 'connected dgram socket reports peer address';
    send($udp, "direct", 0);
    my $buf = "";
    my $sender = recv($peer, $buf, 1024, 0);
    is $buf, "direct", 'connected dgram socket sends without destination';
    ok defined($sender), 'connected dgram peer receives sender address';
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
