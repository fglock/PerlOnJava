use strict;
use warnings;
use utf8;
use Test::More tests => 5;

my $command = qq{"$^X" -e "binmode STDOUT; print STDOUT pack q(H*), q(e280a2)"};
my $captured = qx{$command};

ok(!utf8::is_utf8($captured), 'qx capture is not UTF-8 flagged');
is(length($captured), 3, 'qx capture retains three octets');
is(unpack('H*', $captured), 'e280a2', 'qx capture retains exact UTF-8 bytes');

my $encoded = "\x{2022}";
utf8::encode($encoded);
is($captured, $encoded, 'qx bytes equal explicitly encoded Unicode output');
is(join(',', map { ord } split //, $captured), '226,128,162',
    'qx exposes subprocess octets without decoding');
