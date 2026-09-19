use strict;
use warnings;
use Test::More;
use Config;

sub identity { return shift }
my @repeated = (identity('foo') x 3);
is_deeply(\@repeated, ['foofoofoo'], 'unparenthesized call repeats its scalar result in list context');
my @list_repeated = ((identity('foo')) x 3);
is_deeply(\@list_repeated, ['foo', 'foo', 'foo'], 'parenthesized list retains list repetition');

is(pack('u', '0000'), "\$,#`P,```\n", 'default uuencode width and zero sextets');
for my $width (0, 1, 2) {
    is(pack("u$width", '0000'), pack('u', '0000'), "uuencode width $width uses the default");
}
is(pack('u4', 'foofoo'), "#9F]O\n#9F]O\n", 'uuencode width rounds down to triplets');
is(pack('u*', '0000'), pack('u', '0000'), 'star uses the default uuencode width');
{
    my $warning;
    local $SIG{__WARN__} = sub { $warning = shift };
    my $out = pack('u99', identity('foo') x 99);
    like($warning, qr/Field too wide in 'u' format in pack/, 'wide uuencode field warns');
    is($out, ("_" . '9F]O' x 21 . "\n") x 4 . 'M' . '9F]O' x 15 . "\n",
       'wide uuencode field clamps to 63 bytes without losing repeated input');
}

is(pack('aU', 'b', 8188), "b\341\277\274", 'U after a byte directive emits UTF-8 octets');
is(pack('a(U0)U', 'b', 8188), "b\341\277\274", 'group U0 does not change outer U mode');
ok(utf8::is_utf8(pack('a(U0)U', 'b', 8188)), 'group U0 still upgrades the result');
is(pack('aU0U', 'b', 8188), "b\x{1ffc}", 'unscoped U0 changes following U mode');
is(pack('a(U)U', 'b', 8188, 8188), "b" . "\341\277\274" x 2,
   'group inherits the outer U mode');

my $octets = "\xf8\xf9\xfa\xfb\xfc\xfd\xfe\xff\5\6";
my $upgraded = $octets;
utf8::upgrade($upgraded);
is(pack('u', $upgraded), pack('u', $octets), 'uuencode preserves octets regardless of UTF-8 flag');
is(unpack('u', pack('u', $octets)), $octets, 'uuencode round trips high octets');
is(unpack('u', "&8%P:\n#9F]O\n"), "`\\\32\0\0\0foo",
   'trimmed uuencode lines zero-fill and preserve the next line');

my $pointer_source = "abc\xa5\0\xfede";
for my $order ('', '<', '>') {
    my $pointer = pack("p$order", $pointer_source);
    is(unpack("p$order", $pointer), "abc\xa5", "p$order stops at NUL");
    is(unpack("P${order}7", $pointer), "abc\xa5\0\xfed", "P$order reads counted bytes through NUL");
    utf8::upgrade($pointer);
    is(unpack("p$order", $pointer), "abc\xa5", "p$order accepts an upgraded pointer string");
}

my $native_ones = "\xff" x length(pack('L!', 0));
is(pack('L!', unpack('L!', $native_ones)), $native_ones,
   'native unsigned long preserves every bit without a floating-point conversion');
for my $template ('aC/UU', 'aC/CU') {
    is(join(',', unpack($template, "b\0\341\277\274")), 'b,8188',
       "$template synchronizes U after a zero count prefix");
}
for my $template ('aU0C/UU', 'aU0C/CU') {
    is(join(',', unpack($template, "b\0\341\277\274")), 'b,225',
       "$template preserves U0 after a zero count prefix");
}

is(join('', unpack('(@1a @0a @2)*', 'abcd')), 'badc',
   'absolute unpack offsets are relative to each group repetition');
is_deeply([unpack('(@1c)((@2c)@3c)', "\0\1\0\0\2\3")], [1, 2, 3],
          'nested unpack groups establish separate offset bases');
is(length(pack('D', 0)), $Config::Config{longdblsize},
   'Config reports the implemented long-double pack width');
is(unpack('%54Q', pack('Q', 18014398509481983)), 18014398509481983,
   'wide Q checksum retains integer precision modulo 2**54');
is(unpack('w', pack('w', '18014398509481982')), '18014398509481982',
   'BER preserves a large exact integer string');
{
    my @env = (a => 'AAA', b => 'BBB');
    my $packed = pack('(S/A* S/A*)' . @env / 2, @env);
    eval { my @ignored = unpack('(S/A* S/A*)' . @env / 2, substr($packed, 0, 11)) };
    like($@, qr/length\/code after end of string/,
         'a truncated finite repeated group rejects a missing length code');
}
{
    sub pointer_temporary { my $value = 'a'; return $value . $value++ . $value++ }
    my $warning;
    local $SIG{__WARN__} = sub { $warning .= shift };
    my $pointer = pack('p', pointer_temporary());
    like($warning, qr/temporary value/, 'packing a temporary pointer warns');
}
{
    my $warning;
    local $SIG{__WARN__} = sub { $warning .= shift };
    my $packed = pack('C(C,C)C,C', 65 .. 71);
    is(() = $warning =~ /Invalid type ','/g, 1, 'nested commas warn once per pack call');
}

done_testing();
