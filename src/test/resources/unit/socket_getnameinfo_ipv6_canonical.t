use strict;
use warnings;

use Test::More tests => 3;
use Socket qw(
    AF_INET6 NI_NUMERICHOST NI_NUMERICSERV getnameinfo inet_pton
    pack_sockaddr_in6
);

for my $address ('::', '::1', '2001:db8::1') {
    my ($error, $host, $service) = getnameinfo(
        pack_sockaddr_in6(1234, inet_pton(AF_INET6, $address)),
        NI_NUMERICHOST | NI_NUMERICSERV,
    );
    is_deeply([$error, $host, $service], ['', $address, '1234'],
        "numeric IPv6 address $address uses canonical compression");
}
