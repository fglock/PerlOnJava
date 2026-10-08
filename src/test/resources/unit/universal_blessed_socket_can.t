use v5.10;
use strict;
use warnings;
use Test::More;
use IO::Socket::IP;

my $listener = IO::Socket::IP->new(
    LocalHost => '127.0.0.1',
    LocalPort => 0,
    Listen => 1,
);
plan skip_all => "could not create local listener: $!" unless $listener;

ok($listener->can('peerhost'), 'blessed socket can find peerhost');
ok($listener->can('peerport'), 'blessed socket can find peerport');
ok(UNIVERSAL::can($listener, 'peerhost'), 'UNIVERSAL::can finds socket method');

done_testing;
