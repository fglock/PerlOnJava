use strict;
use warnings;
use Test::More;
use Encode qw(decode encode FB_PERLQQ);

my $euc_extension = decode('euc-jp', pack('C*', 0xAD, 0xA1));
is(ord($euc_extension), 0x2460,
   'EUC-JP decodes the row-13 circled digit used by the upstream regression');

my $input = "value\x{2460}";
is(unpack('H*', encode('Shift_JIS', $input)), '76616c75653f',
   'default encoding replaces an unrepresentable character with question mark');
is(encode('Shift_JIS', $input, FB_PERLQQ), 'value\\x{2460}',
   'FB_PERLQQ preserves the original code point in its escape');
is(encode('Shift_JIS', $euc_extension, FB_PERLQQ), '\\x{2460}',
   'the decoded EUC-JP character survives Shift_JIS FB_PERLQQ encoding');

done_testing;
