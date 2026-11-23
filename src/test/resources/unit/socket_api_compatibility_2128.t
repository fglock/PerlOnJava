use strict;
use warnings;
use Socket qw(AF_INET SOCK_STREAM AI_NUMERICHOST NI_NUMERICHOST NI_NUMERICSERV
    NIx_NOHOST NIx_NOSERV inet_aton pack_sockaddr_in getaddrinfo getnameinfo);
use Test::More;

my ($success, @results) = getaddrinfo('127.0.0.1', 'ftp', {
    socktype => SOCK_STREAM,
});
is("$success", '', 'successful getaddrinfo keeps its empty string form');
is(0 + $success, 0, 'successful getaddrinfo has numeric zero status');
ok(@results, 'successful getaddrinfo returns an address record');

my ($error) = getaddrinfo('localhost', 'ftp', {
    flags => AI_NUMERICHOST,
    socktype => SOCK_STREAM,
});
ok($error, 'AI_NUMERICHOST rejects a hostname');

for my $bad_hints ('invalid', []) {
    my $ok = eval { getaddrinfo('127.0.0.1', '80', $bad_hints); 1 };
    ok(!$ok, 'getaddrinfo rejects a non-hash hints argument');
}

($error) = getaddrinfo('127.0.0.1', 'not-a-real-service-2128', {
    socktype => SOCK_STREAM,
});
ok($error, 'getaddrinfo rejects an unknown service');
ok($error != 0, 'unknown service returns a nonzero numeric status');

my $sockaddr = pack_sockaddr_in(80, inet_aton('127.0.0.1'));
my ($name_error, $host, $service) = getnameinfo($sockaddr, 0);
is($name_error, '', 'getnameinfo succeeds');
is(0 + $name_error, 0, 'successful getnameinfo has numeric zero status');
is($service, 'http', 'getnameinfo resolves a service name by default');

($name_error, $host, $service) = getnameinfo($sockaddr, NI_NUMERICSERV);
is($service, '80', 'NI_NUMERICSERV returns the numeric port');

($name_error, $host, $service) = getnameinfo($sockaddr, 0, NIx_NOHOST | NIx_NOSERV);
ok(!defined($host), 'NIx_NOHOST omits the host');
ok(!defined($service), 'NIx_NOSERV omits the service');

my $packed_loopback = inet_aton('127.0.0.1');
my $host_scalar = scalar CORE::gethostbyaddr($packed_loopback, AF_INET);
ok(defined($host_scalar) && length($host_scalar), 'gethostbyaddr scalar context returns a hostname');
my $service_scalar = scalar CORE::getservbyport(80, 'tcp');
is($service_scalar, 'http', 'getservbyport scalar context returns a service name');

done_testing();
