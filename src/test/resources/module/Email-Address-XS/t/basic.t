use strict;
use warnings;
use Test::More tests => 5;
use Email::Address::XS qw(parse_email_addresses compose_address split_address);

my ($user, $host) = split_address('alice@example.test');
is($user, 'alice', 'split local part');
is($host, 'example.test', 'split host');
is(compose_address($user, $host), 'alice@example.test', 'compose address');
my ($address) = parse_email_addresses('Alice <alice@example.test>');
is($address->address, 'alice@example.test', 'parse address object');
is($address->name, 'Alice', 'parse display name');
