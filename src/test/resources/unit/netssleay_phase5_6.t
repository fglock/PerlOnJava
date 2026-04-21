#!/usr/bin/perl
# Phase 5 (HMAC) + Phase 6 (BIGNUM, RSA) regression test.
#
# Uses RFC 4231 HMAC-SHA test vectors plus self-consistent RSA
# encrypt/decrypt and sign/verify round-trips.

use strict;
use warnings;
use Test::More;
use Net::SSLeay;

Net::SSLeay::load_error_strings();
Net::SSLeay::library_init();

# -----------------------------------------------------------------
# Phase 5: HMAC
# -----------------------------------------------------------------

# RFC 4231 test case 1: HMAC-SHA256, 20-byte key of 0x0b, data "Hi There"
my $md_sha256 = Net::SSLeay::EVP_get_digestbyname('sha256');
ok($md_sha256, 'EVP_get_digestbyname(sha256) returns handle');

my $key  = "\x0b" x 20;
my $data = "Hi There";

# One-shot HMAC
my $mac = Net::SSLeay::HMAC($md_sha256, $key, $data);
is(unpack('H*', $mac),
   'b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7',
   'HMAC() one-shot matches RFC 4231 sha256 test case 1');

# Incremental HMAC_CTX path
my $ctx = Net::SSLeay::HMAC_CTX_new();
ok($ctx, 'HMAC_CTX_new returns a handle');

ok(Net::SSLeay::HMAC_Init_ex($ctx, $key, length $key, $md_sha256, undef),
   'HMAC_Init_ex succeeds');
ok(Net::SSLeay::HMAC_Update($ctx, substr($data, 0, 3)), 'HMAC_Update part 1');
ok(Net::SSLeay::HMAC_Update($ctx, substr($data, 3)),    'HMAC_Update part 2');
my $mac2 = Net::SSLeay::HMAC_Final($ctx);
is(unpack('H*', $mac2), unpack('H*', $mac),
   'Incremental HMAC matches one-shot');

# Reset + reinit with a new algorithm
my $md_sha1 = Net::SSLeay::EVP_get_digestbyname('sha1');
ok(Net::SSLeay::HMAC_CTX_reset($ctx), 'HMAC_CTX_reset');
ok(Net::SSLeay::HMAC_Init_ex($ctx, $key, length $key, $md_sha1, undef),
   'HMAC_Init_ex after reset');
ok(Net::SSLeay::HMAC_Update($ctx, $data), 'HMAC_Update sha1');
my $mac_sha1 = Net::SSLeay::HMAC_Final($ctx);
is(length $mac_sha1, 20, 'HMAC-SHA1 output is 20 bytes');

ok(Net::SSLeay::HMAC_CTX_free($ctx), 'HMAC_CTX_free');

# -----------------------------------------------------------------
# Phase 6: BIGNUM
# -----------------------------------------------------------------

my $bn = Net::SSLeay::BN_new();
ok($bn, 'BN_new');
ok(Net::SSLeay::BN_add_word($bn, 42), 'BN_add_word 42');
is(Net::SSLeay::BN_bn2dec($bn), '42', 'BN_bn2dec after add_word');

my $bn2 = Net::SSLeay::BN_hex2bn("CAFEBABE");
ok($bn2, 'BN_hex2bn');
is(Net::SSLeay::BN_bn2hex($bn2), 'CAFEBABE', 'BN_bn2hex round-trip');
is(Net::SSLeay::BN_bn2dec($bn2), '3405691582', 'BN_bn2dec CAFEBABE');

my $bn3 = Net::SSLeay::BN_dec2bn("1234567890123456789");
is(Net::SSLeay::BN_bn2dec($bn3), '1234567890123456789',
   'BN_dec2bn/BN_bn2dec large number');

# Binary round-trip: bin2bn(x).bn2bin() == x for non-negative x
my $raw = "\x01\x02\x03\x04\xff\x00\x42";
my $bn4 = Net::SSLeay::BN_bin2bn($raw);
is(Net::SSLeay::BN_bn2bin($bn4), $raw,
   'BN_bin2bn / BN_bn2bin round-trip');

is(Net::SSLeay::BN_num_bytes($bn4), length $raw,
   'BN_num_bytes matches raw length');

Net::SSLeay::BN_free($_) for $bn, $bn2, $bn3, $bn4;

# -----------------------------------------------------------------
# Phase 6: RSA encrypt/decrypt + sign/verify round-trip
# -----------------------------------------------------------------

# 2048 is slow; 1024 keeps the test fast
my $rsa = Net::SSLeay::RSA_generate_key(1024, 65537);
ok($rsa, 'RSA_generate_key(1024)');
my $size = Net::SSLeay::RSA_size($rsa);
is($size, 128, 'RSA_size returns 128 for 1024-bit key');

# Encrypt with public key, decrypt with private key
my $plain = "hello, ssleay";
my $ct = '';
my $n = Net::SSLeay::RSA_public_encrypt($plain, $ct, $rsa, 1);
is($n, 128, 'RSA_public_encrypt returns 128 (PKCS1 padding, 1024-bit key)');

my $pt = '';
my $m = Net::SSLeay::RSA_private_decrypt($ct, $pt, $rsa, 1);
is($pt, $plain, 'RSA_private_decrypt recovers plaintext');

# Private-encrypt / public-decrypt (the sign-by-hand path)
my $ct2 = '';
Net::SSLeay::RSA_private_encrypt($plain, $ct2, $rsa, 1);
my $pt2 = '';
Net::SSLeay::RSA_public_decrypt($ct2, $pt2, $rsa, 1);
is($pt2, $plain, 'RSA_private_encrypt / RSA_public_decrypt round-trip');

# RSA_sign / RSA_verify on a SHA-256 digest NID
my $message = "The quick brown fox jumps over the lazy dog";
my $digest_nid = Net::SSLeay::EVP_get_digestbyname('sha256');
my $sig = Net::SSLeay::RSA_sign($digest_nid, $message, $rsa);
ok(defined $sig && length $sig == 128, 'RSA_sign returns 128-byte signature');
is(Net::SSLeay::RSA_verify($digest_nid, $message, $sig, $rsa), 1,
   'RSA_verify succeeds for matching signature');
is(Net::SSLeay::RSA_verify($digest_nid, "tampered", $sig, $rsa), 0,
   'RSA_verify fails for tampered message');

Net::SSLeay::RSA_free($rsa);

done_testing();
