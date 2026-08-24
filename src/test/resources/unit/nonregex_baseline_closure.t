#!/usr/bin/env perl
use strict;
use warnings;
use feature 'evalbytes';
use Test::More;
use Socket qw(AF_INET AF_UNIX PF_UNSPEC SOCK_STREAM IPPROTO_TCP
              SOL_SOCKET SO_SNDBUF INADDR_LOOPBACK sockaddr_in);

for my $ord (0x04, 0x1a) {
    my $chr = chr $ord;
    evalbytes "\$$chr";
    like $@, qr/ syntax\ error | Unrecognized\ character /x,
        sprintf('evalbytes rejects $ followed by control byte %02x', $ord);
    utf8::upgrade($chr);
    eval "no strict; \$$chr = 4;";
    like $@, qr/ syntax\ error | Unrecognized\ character /x,
        sprintf('unicode eval rejects $ followed by control byte %02x', $ord);
}

socket(my $server, AF_INET, SOCK_STREAM, IPPROTO_TCP) or die "socket: $!";
ok bind($server, sockaddr_in(0, INADDR_LOOPBACK)), 'bind an unconnected stream socket';

use constant SNDBUF_SIZE => 32768;
sub stringify_constant_once { SNDBUF_SIZE . '' }
setsockopt($server, SOL_SOCKET, SO_SNDBUF, SNDBUF_SIZE) or die "setsockopt: $!";
my $sndbuf = unpack 'i', getsockopt($server, SOL_SOCKET, SO_SNDBUF);
ok $sndbuf == SNDBUF_SIZE || $sndbuf == 2 * SNDBUF_SIZE,
    'once-stringified integer constant survives setsockopt/getsockopt';

socketpair(my $left, my $right, AF_UNIX, SOCK_STREAM, PF_UNSPEC)
    or die "socketpair: $!";
syswrite($right, "ready\n");
is scalar(<$left>), "ready\n", 'PF_UNIX socketpair is bidirectional';

done_testing;
