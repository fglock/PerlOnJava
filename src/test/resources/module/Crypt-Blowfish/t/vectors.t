use strict;
use warnings;
use Test::More;
use Crypt::Blowfish;

my @vectors = (
    [ '6162636465666768696a6b6c6d6e6f707172737475767778797a', '424c4f5746495348', '324ed0fef413a203' ],
    [ '57686f206973204a6f686e2047616c743f',                 'fedcba9876543210', 'cc91732b8022f684' ],
    [ '0000000000000000',                                   '0000000000000000', '4ef997456198dd78' ],
    [ 'ffffffffffffffff',                                   '0000000000000000', 'f21e9a77b71c49bc' ],
    [ 'ffffffffffffffff',                                   'ffffffffffffffff', '51866fd5b85ecb8a' ],
    [ '3000000000000000',                                   '1000000000000001', '7d856f9a613063f2' ],
    [ '1111111111111111',                                   '1111111111111111', '2466dd878b963c9d' ],
    [ '584023641aba6176',                                   '004bd6ef09176062', '452031c1e4fada8e' ],
    [ '0101010101010101',                                   '0123456789abcdef', 'fa34ec4847b268b2' ],
);

for my $vector (@vectors) {
    my ($key_hex, $plain_hex, $cipher_hex) = @$vector;
    my $cipher = Crypt::Blowfish->new(pack('H*', $key_hex));
    is(unpack('H*', $cipher->encrypt(pack('H*', $plain_hex))), $cipher_hex,
       "encrypt vector $plain_hex");
    is(unpack('H*', $cipher->decrypt(pack('H*', $cipher_hex))), $plain_hex,
       "decrypt vector $cipher_hex");
}

is(Crypt::Blowfish::blocksize(), 8, 'block size');
is(Crypt::Blowfish::min_keysize(), 8, 'minimum key size');
is(Crypt::Blowfish::max_keysize(), 56, 'maximum key size');

done_testing;
