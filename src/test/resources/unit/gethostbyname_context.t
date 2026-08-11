use strict;
use warnings;
use Test::More;

my $packed = gethostbyname('127.0.0.1');
is(length($packed), 4, 'scalar gethostbyname returns a packed IPv4 address');
is(unpack('H*', $packed), '7f000001', 'scalar address contains the requested IPv4 bytes');

my @host = gethostbyname('127.0.0.1');
is(scalar @host, 5, 'list gethostbyname returns the host entry fields');
is(length($host[4]), 4, 'list address field remains packed');

done_testing;
