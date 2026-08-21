use strict;
use warnings;
use Test::More;

my $byte_nel = pack 'C', 0x85;
utf8::downgrade($byte_nel, 1);

my $byte_lf = "\n";
utf8::downgrade($byte_lf, 1);

like($byte_nel, qr/\A\v\z/i,
    'case-insensitive byte pattern matches byte NEL');
like($byte_lf, qr/\A\v\z/i,
    'case-insensitive byte pattern matches byte LF');
is(qr/\b\v$/i, '(?^i:\b\v$)',
    'case-insensitive vertical whitespace pattern stringifies');

done_testing;
