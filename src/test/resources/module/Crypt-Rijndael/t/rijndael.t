use strict;
use warnings;
use Test::More;
use Crypt::Rijndael;

is(Crypt::Rijndael->keysize, 32, 'keysize');
is(Crypt::Rijndael->blocksize, 16, 'blocksize');

my $plain = pack 'H*', '6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710';
my $iv = pack 'H*', '000102030405060708090a0b0c0d0e0f';
my @vectors = (
    [ MODE_ECB => '603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4', undef, 'f3eed1bdb5d2a03c064b5a7e3db181f8591ccb10d410ed26dc5ba74a31362870b6ed21b99ca6f4f9f153e7b1beafed1d23304b7a39f9f3ff067d8d8f9e24ecc7' ],
    [ MODE_CBC => '2b7e151628aed2a6abf7158809cf4f3c', $iv, '7649abac8119b246cee98e9b12e9197d5086cb9b507219ee95db113a917678b273bed6b8e3c1743b7116e69e222295163ff1caa1681fac09120eca307586e1a7' ],
    [ MODE_CFB => '2b7e151628aed2a6abf7158809cf4f3c', $iv, '3b3fd92eb72dad20333449f8e83cfb4ac8a64537a0b3a93fcde3cdad9f1ce58b26751f67a3cbb140b1808cf187a4f4dfc04b05357c5d1c0eeac4c66f9ff7f2e6' ],
    [ MODE_OFB => '2b7e151628aed2a6abf7158809cf4f3c', $iv, '3b3fd92eb72dad20333449f8e83cfb4a7789508d16918f03f53c52dac54ed8259740051e9c5fecf64344f7a82260edcc304c6528f659c77866a510d9c1d6ae5e' ],
    [ MODE_CTR => '2b7e151628aed2a6abf7158809cf4f3c', pack('H*', 'f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff'), '874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff5ae4df3edbd5d35e5b4f09020db03eab1e031dda2fbe03d1792170a0f3009cee' ],
);

for my $vector (@vectors) {
    my ($constant, $key_hex, $vector_iv, $cipher_hex) = @$vector;
    my $mode = Crypt::Rijndael->$constant();
    my $cipher = Crypt::Rijndael->new(pack('H*', $key_hex), $mode);
    $cipher->set_iv($vector_iv) if defined $vector_iv;
    my $encrypted = $cipher->encrypt($plain);
    is unpack('H*', $encrypted), $cipher_hex, "$constant NIST vector";
    is $cipher->decrypt($encrypted), $plain, "$constant decrypts";
}

my $partial = 'partial CFB/OFB/CTR data';
for my $mode (Crypt::Rijndael::MODE_CFB(), Crypt::Rijndael::MODE_OFB(), Crypt::Rijndael::MODE_CTR()) {
    my $cipher = Crypt::Rijndael->new('k' x 16, $mode);
    is $cipher->decrypt($cipher->encrypt($partial, $iv), $iv), $partial, "mode $mode supports partial blocks";
}

done_testing;
