use strict;
use warnings;
use Test::More;
use Crypt::Blowfish;

my @vectors = (
    ['0000000000000000', '0000000000000000', '4ef997456198dd78'],
    ['ffffffffffffffff', 'ffffffffffffffff', '51866fd5b85ecb8a'],
    ['3000000000000000', '1000000000000001', '7d856f9a613063f2'],
    ['1111111111111111', '1111111111111111', '2466dd878b963c9d'],
    ['0101010101010101', '0123456789abcdef', 'fa34ec4847b268b2'],
);

for my $vector (@vectors) {
    my ($key_hex, $plain_hex, $cipher_hex) = @$vector;
    my $cipher = Crypt::Blowfish->new(pack('H*', $key_hex));
    my $encrypted = $cipher->encrypt(pack('H*', $plain_hex));
    is(unpack('H*', $encrypted), $cipher_hex, "encrypt $plain_hex");
    is(unpack('H*', $cipher->decrypt($encrypted)), $plain_hex, "decrypt $cipher_hex");
}

is(Crypt::Blowfish::blocksize(), 8, 'block size');
is(Crypt::Blowfish::min_keysize(), 8, 'minimum key size');
is(Crypt::Blowfish::max_keysize(), 56, 'maximum key size');

done_testing;
