use strict;
use warnings;
use Test::More tests => 4;
use bytes;
use Encode qw(is_utf8);

my $octets = "\xFF" x 8;
ok(!is_utf8($octets), 'high byte input is unflagged');

$octets =~ /^(.+)$/s;
is(length($1), 8, 'capture length is the byte length');
ok(!is_utf8($1), 'capture remains an unflagged byte string');
is(unpack('H*', $1), 'ff' x 8, 'capture retains the original octets');
