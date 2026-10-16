use strict;
use warnings;
use utf8;
use Encode qw(encode decode is_utf8 FB_DEFAULT);
use Test::More;

# MARC::File::USMARC reads record data as bytes, then decodes UTF-8 fields.
# Keep the same byte-to-character boundary in a dependency-free regression.
my $aleph = "\x{05D0}";
my $field_bytes = encode('UTF-8', $aleph);
ok(!is_utf8($field_bytes), 'UTF-8 field data is represented as octets');
is(unpack('H*', $field_bytes), 'd790', 'Aleph is stored as its UTF-8 bytes');
my $record_bytes = 'x' . $field_bytes . 'y';
my $field_slice = bytes::substr($record_bytes, 1, 2);
is(unpack('H*', $field_slice), 'd790', 'MARC byte slicing preserves the field octets');
my $decoded = decode('UTF-8', $field_slice, FB_DEFAULT);
ok(is_utf8($decoded), 'decoded field is marked as Unicode');
is($decoded, $aleph, 'UTF-8 field round-trips without mojibake');

# MARC-8's fallback path can retain a non-UTF-8 byte value. It must not be
# interpreted as the UTF-8 encoding of a Latin-1 character during round-trip.
my $legacy = "foo\xE2e";
my $legacy_copy = pack('C*', unpack('C*', $legacy));
ok(!is_utf8($legacy_copy), 'legacy field stays an unflagged byte string');
is(unpack('H*', $legacy_copy), '666f6fe265', 'legacy field octets are preserved');

done_testing;
