use strict;
use warnings;
use Test::More tests => 6;
use Socket6 qw(AF_INET6 NI_NUMERICSERV NI_NUMERICSERVER inet_pton inet_ntop pack_sockaddr_in6_all unpack_sockaddr_in6_all);

my $packed = inet_pton(AF_INET6, '::1');
is(length($packed), 16, 'IPv6 presentation converts to 16 octets');
is(inet_ntop(AF_INET6, $packed), '::1', 'IPv6 octets convert back');
my $sockaddr = pack_sockaddr_in6_all(4242, 7, $packed, 3);
my ($port, $flow, $addr, $scope) = unpack_sockaddr_in6_all($sockaddr);
is($port, 4242, 'port round trips');
is($flow, 7, 'flow info round trips');
is($scope, 3, 'scope id round trips');
is(NI_NUMERICSERVER, NI_NUMERICSERV, 'historical numeric-service alias is exported');
