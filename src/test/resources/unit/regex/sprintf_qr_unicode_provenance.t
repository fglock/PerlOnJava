use strict;
use warnings;
use utf8;
use Test::More;

my $unicode_qr = qr/(?:alpha|测试)/i;
my $formatted = sprintf '(?:%s)', $unicode_qr;
ok(utf8::is_utf8($formatted),
    'sprintf preserves the Unicode flag when stringifying a Unicode qr');

my $recompiled = eval { qr/$formatted/ };
ok(defined($recompiled), 'stringified Unicode qr can be compiled again')
    or diag($@);
like('测试', $recompiled,
    'recompiled stringified qr still matches its Unicode alternative')
    if $recompiled;

my $ascii_qr = qr/alpha/i;
my $ascii_formatted = sprintf '(?:%s)', $ascii_qr;
ok(!utf8::is_utf8($ascii_formatted),
    'sprintf keeps an ASCII qr string byte-backed');

done_testing;
