use strict;
use warnings;
use utf8;
use Test::More;

my $sharp = chr 0xDF;
utf8::downgrade($sharp, 1);
my $ss = 'ss';
utf8::downgrade($ss, 1);

unlike($ss, qr/$sharp/di, 'd byte pattern and subject do not full-fold sharp s');
like($ss, qr/$sharp/ui, 'u permits sharp-s full folding');
like($ss, qr/$sharp/ai, 'a permits sharp-s full folding');
unlike($ss, qr/$sharp/aai, 'aa rejects ASCII-crossing sharp-s folding');

my $upgraded_sharp = $sharp;
utf8::upgrade($upgraded_sharp);
like($ss, qr/$upgraded_sharp/di, 'd upgraded pattern permits sharp-s full folding');
my $upgraded_ss = $ss;
utf8::upgrade($upgraded_ss);
like($upgraded_ss, qr/$sharp/di, 'd upgraded subject permits sharp-s full folding');

like('I', qr/i/i, 'ordinary I folds with ASCII i');
unlike('I', qr/\x{0131}/i, 'ordinary folding excludes Turkic dotless i');
unlike("\x{0130}", qr/i/i, 'ordinary folding excludes Turkic dotted I');

like("\x{1E9E}\x{1E9E}", qr/(\x{00DF})\1/iaa,
    'aa backreference preserves non-ASCII sharp-s siblings');
unlike('ssss', qr/(\x{00DF})\1/iaa,
    'aa backreference rejects captured ASCII-crossing folds');

done_testing;
