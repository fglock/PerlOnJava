use strict;
use warnings;
use Test::More;
use CryptX;
use Crypt::PRNG qw(random_bytes);

is(Crypt::Misc::encode_b64u("\x00\x01\x02\xff"), 'AAEC_w', 'base64url encodes binary data without padding');
is(Crypt::Misc::decode_b64u('AAEC_w'), "\x00\x01\x02\xff", 'base64url decodes unpadded binary data');
ok(!defined Crypt::Misc::decode_b64u('AA+_'), 'base64url rejects standard base64 alphabet');

is(Crypt::Digest::digest_data_hex('SHA256', 'abc'),
   'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
   'SHA-256 functional digest matches the standard vector');
is(Crypt::Digest::digest_data_b64u('SHA256', 'a', 'bc'),
   'ungWv48Bz-pBQUDeXa4iI7ADYaOWF3qctBD_YfIAFa0',
   'digest accepts multiple data chunks and returns base64url');

is(Crypt::Mac::HMAC::hmac_hex('SHA256', 'key', 'The quick brown fox jumps over the lazy dog'),
   'f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8',
   'HMAC-SHA-256 matches the standard vector');
is(Crypt::Mac::HMAC::hmac_b64u('SHA256', 'key', 'message'),
   'bp7ym3X__Ft6uuUn1Y_a2y_kLnIZARl2kXNDBl9Y7Uo',
   'HMAC returns unpadded base64url');
is(length(random_bytes(32)), 32, 'random_bytes returns the requested length');
is(Crypt::Misc::increment_octets_be("\x01\xff"), "\x02\x00", 'big-endian octet increment');
is(Crypt::Misc::increment_octets_le("\xff\x01"), "\x00\x02", 'little-endian octet increment');
ok(Crypt::Misc::slow_eq('same', 'same'), 'slow_eq accepts equal values');
ok(!Crypt::Misc::slow_eq('same', 'diff'), 'slow_eq rejects different values');

done_testing();
