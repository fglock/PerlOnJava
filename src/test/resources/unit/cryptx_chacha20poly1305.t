use strict;
use warnings;
use Test::More;

plan skip_all => 'CryptX is optional'
    unless eval { require Crypt::AuthEnc::ChaCha20Poly1305; 1 };

my $key = pack 'H*', '808182838485868788898a8b8c8d8e8f'
    . '909192939495969798999a9b9c9d9e9f';
my $nonce = pack 'H*', '070000004041424344454647';
my $aad = pack 'H*', '50515253c0c1c2c3c4c5c6c7';
my $plaintext = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";

my ($ciphertext, $tag) = Crypt::AuthEnc::ChaCha20Poly1305::chacha20poly1305_encrypt_authenticate(
    $key, $nonce, $aad, $plaintext);
is unpack('H*', $ciphertext),
    'd31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6'
        . '3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36'
        . '92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc'
        . '3ff4def08e4b7a9de576d26586cec64b6116',
    'ChaCha20-Poly1305 ciphertext matches RFC 8439';
is unpack('H*', $tag), '1ae10b594f09e26a7e902ecbd0600691',
    'ChaCha20-Poly1305 tag matches RFC 8439';
is Crypt::AuthEnc::ChaCha20Poly1305::chacha20poly1305_decrypt_verify(
    $key, $nonce, $aad, $ciphertext, $tag), $plaintext,
    'authenticated decryption recovers the plaintext';
ok !defined Crypt::AuthEnc::ChaCha20Poly1305::chacha20poly1305_decrypt_verify(
    $key, $nonce, $aad, $ciphertext, pack('H*', '00000000000000000000000000000000')),
    'decryption rejects an invalid authentication tag';

done_testing;
