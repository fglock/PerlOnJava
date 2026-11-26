use strict;
use warnings;
use utf8;
use Encode qw(decode_utf8 decode find_encoding FB_CROAK LEAVE_SRC);
use Test::More;

my $unicode = '天気';
eval { $unicode = decode_utf8($unicode) };
is($unicode, '天気', 'decode_utf8 failure leaves UTF-8-flagged Unicode unchanged');

is(decode_utf8("\xE5\xA4\xA9\xE6\xB0\x97"), '天気', 'decode_utf8 still decodes octets');

my $wide = 'Υ';
my $decoded = eval { decode('UTF-8', $wide, FB_CROAK | LEAVE_SRC) };
like($@, qr/Wide character/, 'decode rejects a wide character instead of replacing it');
is($wide, 'Υ', 'decode leaves wide input unchanged after failure');
is(decode('UTF-8', "\xCE\xA5", FB_CROAK | LEAVE_SRC), 'Υ',
    'decode still accepts UTF-8 octets');

my $emoji = '💚';
eval { decode('UTF-8', $emoji, FB_CROAK | LEAVE_SRC) };
like($@, qr/Wide character/, 'decode rejects supplementary Unicode input');
is($emoji, '💚', 'decode preserves supplementary Unicode input after failure');

{
    package Local::TestCodec;
    use parent 'Encode::Encoding';
    sub decode { return 'decoded:' . $_[1] }
    sub encode { return 'encoded:' . $_[1] }
    __PACKAGE__->Define('local-test-codec', 'ltc');
}

my $codec = find_encoding('ltc');
isa_ok($codec, 'Local::TestCodec', 'custom codec alias resolves');
is(decode('local-test-codec', 'input'), 'decoded:input', 'decode dispatches to custom codec');
is(Encode::encode('local-test-codec', 'input'), 'encoded:input', 'encode dispatches to custom codec');

done_testing;
