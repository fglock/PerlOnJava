use strict;
use warnings;
use Test::More tests => 9;
use Socket qw(
    AF_INET6 SOCK_STREAM AI_PASSIVE getaddrinfo inet_pton
    pack_sockaddr_in6 unpack_sockaddr_in6 getnameinfo
    NI_NUMERICHOST NI_NUMERICSERV
);

my ($error, @records) = getaddrinfo(
    undef,
    0,
    {
        family   => AF_INET6,
        socktype => SOCK_STREAM,
        flags    => AI_PASSIVE,
    },
);

is($error, '', 'passive IPv6 lookup succeeds');
ok(@records, 'passive IPv6 lookup returns a record');
is($records[0]{family}, AF_INET6, 'record has requested IPv6 family');
ok(length($records[0]{addr}) >= 24, 'record contains an IPv6 sockaddr');

ok(socket(my $socket, AF_INET6, SOCK_STREAM, 0),
    'socket accepts the platform IPv6 address-family constant');
SKIP: {
    my $bound = bind($socket, pack_sockaddr_in6(0, inet_pton(AF_INET6, '::1')));
    skip "IPv6 loopback bind unavailable: $!", 4 unless $bound;

    pass('bind accepts a packed IPv6 socket address');
    ok(listen($socket, 1), 'IPv6 stream socket can listen after binding');
    my ($bound_port, $bound_address) = unpack_sockaddr_in6(getsockname($socket));
    ok($bound_port > 0 && length($bound_address) == 16,
        'getsockname returns a packed IPv6 socket address');
    my ($name_error, $numeric_host, $numeric_service) = getnameinfo(
        getsockname($socket), NI_NUMERICHOST | NI_NUMERICSERV,
    );
    is_deeply([$name_error, $numeric_host, $numeric_service],
        ['', '::1', "$bound_port"],
        'getnameinfo decodes a packed IPv6 socket address');
}
close $socket;
