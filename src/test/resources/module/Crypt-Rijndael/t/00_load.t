#! perl
use strict;
use warnings;

use Test::More tests => 40;
use Crypt::Rijndael;

my %flag_for = (
	ecb => Crypt::Rijndael::MODE_ECB,
	cbc => Crypt::Rijndael::MODE_CBC,
	cfb => Crypt::Rijndael::MODE_CFB,
	ofb => Crypt::Rijndael::MODE_OFB,
	ctr => Crypt::Rijndael::MODE_CTR,
);

sub is_crypted {
	my %args = @_;
	local $Test::Builder::Level = $Test::Builder::Level + 1;

	my $cipher = Crypt::Rijndael->new(pack('H*', $args{key}), $flag_for{ $args{mode} });
	$cipher->set_iv(pack 'H*', $args{iv}) if defined $args{iv};

	my $plaintext = pack 'H*', $args{plaintext};
	my $crypted = $cipher->encrypt($plaintext);

	is(unpack("H*", $crypted), $args{ciphertext});
	is($cipher->decrypt($crypted), $plaintext);
}

my $plaintext = unpack "H*", pack "C32", 0 .. 31;
my $key = unpack "H*", pack 'Cx31', 1;

is_crypted(
	name => 'ECB-AES-256-',
	key => $key,
	mode => 'ecb',
	plaintext => $plaintext,
	ciphertext => "f2258e225d794572393a6484cfced7cf925d1aa18366bcd93c33d104294c8a6f",
);

is_crypted(
	name => '',
	key => $key,
	mode => 'cbc',
	plaintext => $plaintext,
	ciphertext => "f2258e225d794572393a6484cfced7cfb487a41f6b6286c00c9c8d80cb3ee9f8",
);

$plaintext = unpack "H*", pack 'C*', map { $_ + ($_ << 4) } 0 .. 0xF;

is_crypted(
	name => 'AES-256',
	key => unpack('H*', pack('C*', 0 .. 31)),
	mode => 'ecb',
	plaintext => $plaintext,
	ciphertext => "8ea2b7ca516745bfeafc49904b496089",
);

is_crypted(
	name => 'AES-192',
	key => unpack( 'H*', pack('C*', 0 .. 23)),
	mode => 'ecb',
	plaintext => $plaintext,
	ciphertext => "dda97ca4864cdfe06eaf70a0ec0d7191",
);

is_crypted(
	name => 'AES-128',
	key => unpack('H*', pack('C*', 0 .. 15)),
	mode => 'ecb',
	plaintext => $plaintext,
	ciphertext => "69c4e0d86a7b0430d8cdb78070b4c55a",
);

# Modes of operation -- NIST paper tests


is_crypted(
	name => 'ECB-AES-128',
	key => "2b7e151628aed2a6abf7158809cf4f3c",
	mode => 'ecb',
	plaintext => "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
	ciphertext => "3ad77bb40d7a3660a89ecaf32466ef97f5d3d58503b9699de785895a96fdbaaf43b1cd7f598ece23881b00e3ed0306887b0c785e27e8ad3f8223207104725dd4",
);

is_crypted(
	name => 'ECB-AES-192',
	key => "8e73b0f7da0e6452c810f32b809079e562f8ead2522c6b7b",
	mode => 'ecb',
	plaintext => "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
	ciphertext => "bd334f1d6e45f25ff712a214571fa5cc974104846d0ad3ad7734ecb3ecee4eefef7afd2270e2e60adce0ba2face6444e9a4b41ba738d6c72fb16691603c18e0e",
);

is_crypted(
	name => 'ECB-AES-256',
	key => "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4",
	mode => 'ecb',
	plaintext => "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
	ciphertext => "f3eed1bdb5d2a03c064b5a7e3db181f8591ccb10d410ed26dc5ba74a31362870b6ed21b99ca6f4f9f153e7b1beafed1d23304b7a39f9f3ff067d8d8f9e24ecc7",
);

is_crypted(
	name => 'CBC-AES-128',
	key => "2b7e151628aed2a6abf7158809cf4f3c",
	mode => 'cbc',
	iv => "000102030405060708090a0b0c0d0e0f",
	plaintext => "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
	ciphertext => "7649abac8119b246cee98e9b12e9197d5086cb9b507219ee95db113a917678b273bed6b8e3c1743b7116e69e222295163ff1caa1681fac09120eca307586e1a7",
);

is_crypted(
	name => 'CBC-AES-192',
	key => "8e73b0f7da0e6452c810f32b809079e562f8ead2522c6b7b",
	mode => 'cbc',
	iv => "000102030405060708090a0b0c0d0e0f",
	plaintext => "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
	ciphertext => "4f021db243bc633d7178183a9fa071e8b4d9ada9ad7dedf4e5e738763f69145a571b242012fb7ae07fa9baac3df102e008b0e27988598881d920a9e64f5615cd",
);

is_crypted(
	name => 'CBC-AES-256',
	key => "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4",
	mode => 'cbc',
	iv => "000102030405060708090a0b0c0d0e0f",
	plaintext => "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
	ciphertext => "f58c4c04d6e5f1ba779eabfb5f7bfbd69cfc4e967edb808d679f777bc6702c7d39f23369a9d9bacfa530e26304231461b2eb05e2c39be9fcda6c19078c6a9d1b",
);

is_crypted(
	name => 'CFB-AES-128',
	key => "2b7e151628aed2a6abf7158809cf4f3c",
	mode => 'cfb',
	iv => "000102030405060708090a0b0c0d0e0f",
	plaintext => "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
	ciphertext => "3b3fd92eb72dad20333449f8e83cfb4ac8a64537a0b3a93fcde3cdad9f1ce58b26751f67a3cbb140b1808cf187a4f4dfc04b05357c5d1c0eeac4c66f9ff7f2e6",
);

is_crypted(
	name => 'CFB-AES-192',
	key => "8e73b0f7da0e6452c810f32b809079e562f8ead2522c6b7b",
	mode => 'cfb',
	iv => "000102030405060708090a0b0c0d0e0f",
	plaintext => "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
	ciphertext => "cdc80d6fddf18cab34c25909c99a417467ce7f7f81173621961a2b70171d3d7a2e1e8a1dd59b88b1c8e60fed1efac4c9c05f9f9ca9834fa042ae8fba584b09ff",
);

is_crypted(
	name => 'CFB-AES-256',
	key => "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4",
	mode => 'cfb',
	iv => "000102030405060708090a0b0c0d0e0f",
	plaintext => "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
	ciphertext => "dc7e84bfda79164b7ecd8486985d386039ffed143b28b1c832113c6331e5407bdf10132415e54b92a13ed0a8267ae2f975a385741ab9cef82031623d55b1e471",
);

is_crypted(
	name => 'OFB-AES-128',
	key => "2b7e151628aed2a6abf7158809cf4f3c",
	mode => 'ofb',
	iv => "000102030405060708090a0b0c0d0e0f",
	plaintext => "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
	ciphertext => "3b3fd92eb72dad20333449f8e83cfb4a7789508d16918f03f53c52dac54ed8259740051e9c5fecf64344f7a82260edcc304c6528f659c77866a510d9c1d6ae5e",
);

# OFB-AES-192
is_crypted(
	name => 'OFB-AES-192',
	key => "8e73b0f7da0e6452c810f32b809079e562f8ead2522c6b7b",
	mode => 'ofb',
	iv => "000102030405060708090a0b0c0d0e0f",
	plaintext => "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
	ciphertext => "cdc80d6fddf18cab34c25909c99a4174fcc28b8d4c63837c09e81700c11004018d9a9aeac0f6596f559c6d4daf59a5f26d9f200857ca6c3e9cac524bd9acc92a",
);

# OFB-AES-256
is_crypted(
	name => 'OFB-AES-256',
	key => "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4",
	mode => 'ofb',
	iv => "000102030405060708090a0b0c0d0e0f",
	plaintext => "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
	ciphertext => "dc7e84bfda79164b7ecd8486985d38604febdc6740d20b3ac88f6ad82a4fb08d71ab47a086e86eedf39d1c5bba97c4080126141d67f37be8538f5a8be740e484",
);

is_crypted(
	name => 'CTR-AES-128',
	key => "2b7e151628aed2a6abf7158809cf4f3c",
	mode => 'ctr',
	iv => "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
	plaintext => "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
	ciphertext => "874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff5ae4df3edbd5d35e5b4f09020db03eab1e031dda2fbe03d1792170a0f3009cee",
);

is_crypted(
	name => 'CTR-AES-192',
	key => "8e73b0f7da0e6452c810f32b809079e562f8ead2522c6b7b",
	mode => 'ctr',
	iv => "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
	plaintext => "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
	ciphertext => "1abc932417521ca24f2b0459fe7e6e0b090339ec0aa6faefd5ccc2c6f4ce8e941e36b26bd1ebc670d1bd1d665620abf74f78a7f6d29809585a97daec58c6b050",
);

is_crypted(
	name => 'CTR-AES-256',
	key => "603deb1015ca71be2b73aef0857d77811f352c073b6108d72d9810a30914dff4",
	mode => 'ctr',
	iv => "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff",
	plaintext => "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710",
	ciphertext => "601ec313775789a5b7a7f504bbf3d228f443e3ca4d62b59aca84e990cacaf5c52b0930daa23de94ce87017ba2d84988ddfc9c58db67aada613c2dd08457941a6",
);

