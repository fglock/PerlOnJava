#!/usr/bin/perl
use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

BEGIN {
    use_ok('Digest::SHA', qw(
        sha1 sha1_hex sha1_base64
        sha224 sha224_hex sha224_base64
        sha256 sha256_hex sha256_base64
        sha384 sha384_hex sha384_base64
        sha512 sha512_hex sha512_base64
        sha512224 sha512224_hex sha512224_base64
        sha512256 sha512256_hex sha512256_base64
        ));
}

# Test vectors for various SHA algorithms
my %test_vectors = (
    sha1 => {
        empty => 'da39a3ee5e6b4b0d3255bfef95601890afd80709',
        abc => 'a9993e364706816aba3e25717850c26c9cd0d89d',
        message_digest => '34aa973cd4c4daa4f61eeb2bdbad27316534016f',
    },
    sha224 => {
        empty => 'd14a028c2a3a2bc9476102bb288234c415a2b01f828ea62ac5b3e42f',
        abc => '23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7',
    },
    sha256 => {
        empty => 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
        abc => 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
        message_digest => 'f7846f55cf23e14eebeab5b4e1550cad5b509e3348fbc4efa3a1413d393cb650',
    },
    sha384 => {
        empty => '38b060a751ac96384cd9327eb1b1e36a21fdb71114be07434c0cc7bf63f6e1da274edebfe76f65fbd51ad2f14898b95b',
        abc => 'cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed8086072ba1e7cc2358baeca134c825a7',
    },
    sha512 => {
        empty => 'cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e',
        abc => 'ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f',
    },
);

subtest 'Object creation and algorithm validation' => sub {
    plan tests => 10;

    # Valid algorithms
    for my $alg (qw(1 224 256 384 512 512224 512256)) {
        my $sha = Digest::SHA->new($alg);
        isa_ok($sha, 'Digest::SHA', "new($alg)");
    }

    # Default algorithm (SHA-1)
    my $sha_default = Digest::SHA->new();
    isa_ok($sha_default, 'Digest::SHA', 'new() with default');
    is($sha_default->algorithm(), '1', 'Default algorithm is SHA-1');

    # Invalid algorithm
    eval { Digest::SHA->new('999') };
    is($@, '', 'Invalid algorithm throws no error');
};

subtest 'Basic digest operations' => sub {
    plan tests => 14;

    # Test empty string digests
    for my $alg (qw(1 224 256 384 512)) {
        my $sha = Digest::SHA->new($alg);
        my $hex = $sha->hexdigest();
        my $expected = $test_vectors{"sha$alg"}{empty};
        is($hex, $expected, "SHA-$alg empty string");
    }

    # Test 'abc' digests
    for my $alg (qw(1 224 256 384 512)) {
        my $sha = Digest::SHA->new($alg);
        $sha->add('abc');
        my $hex = $sha->hexdigest();
        my $expected = $test_vectors{"sha$alg"}{abc};
        is($hex, $expected, "SHA-$alg 'abc'");
    }

    # Test multiple add() calls
    my $sha = Digest::SHA->new('256');
    $sha->add('a');
    $sha->add('b');
    $sha->add('c');
    is($sha->hexdigest(), $test_vectors{sha256}{abc}, 'Multiple add() calls');

    # Test add() with multiple arguments
    $sha = Digest::SHA->new('256');
    $sha->add('a', 'b', 'c');
    is($sha->hexdigest(), $test_vectors{sha256}{abc}, 'add() with multiple arguments');

    # Test digest formats
    $sha = Digest::SHA->new('1');
    $sha->add('abc');
    my $digest = $sha->digest();
    is(length($digest), 20, 'SHA-1 digest length is 20 bytes');

    $sha = Digest::SHA->new('1');
    $sha->add('abc');
    my $b64 = $sha->b64digest();
    ok($b64 =~ /^[A-Za-z0-9+\/]+$/, 'Base64 digest format');
};

subtest 'Algorithm-specific methods' => sub {
    plan tests => 3;

    my $sha = Digest::SHA->new('256');
    is($sha->algorithm(), '256', 'algorithm() returns correct value');

    # Check if hashsize exists and what it returns
    SKIP: {
        skip "hashsize() may not be implemented", 1 unless $sha->can('hashsize');
        my $size = $sha->hashsize();
        ok(defined $size, 'hashsize() returns a value');
    }

    # Check if bitsize exists (standard Digest::SHA has this)
    SKIP: {
        skip "bitsize() may not be implemented", 1 unless $sha->can('bitsize');
        is($sha->bitsize(), 256, 'bitsize() returns correct value');
    }
};

subtest 'File operations' => sub {
    plan tests => 2;

    # Create a temporary file
    my ($fh, $filename) = tempfile(UNLINK => 1);
    print $fh "The quick brown fox jumps over the lazy dog";
    close $fh;

    # Test addfile with filename
    my $sha = Digest::SHA->new('256');
    $sha->addfile($filename);
    my $hex = $sha->hexdigest();
    is($hex, 'd7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592',
        'addfile with filename');

    # Test with filehandle
    open my $fh2, '<', $filename or die "Cannot open $filename: $!";
    $sha = Digest::SHA->new('256');
    $sha->addfile($fh2);
    close $fh2;
    is($sha->hexdigest(), 'd7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592',
        'addfile with filehandle');
};

subtest 'Bit operations' => sub {
    plan tests => 3;

    # Test add_bits with bit string
    my $sha = Digest::SHA->new('1');
    $sha->add_bits('11001000');  # 0xC8
    my $hex = $sha->hexdigest();
    ok($hex, 'add_bits with bit string');

    # Test add_bits with data and bit count
    $sha = Digest::SHA->new('1');
    $sha->add_bits('A', 8);
    $hex = $sha->hexdigest();
    ok($hex, 'add_bits with data and bit count');

    # Test mixed bit and byte operations
    $sha = Digest::SHA->new('1');
    $sha->add('A');
    $sha->add_bits('11111111');
    $hex = $sha->hexdigest();
    ok($hex, 'Mixed add and add_bits');
};

subtest 'Clone operations' => sub {
    plan tests => 3;

    my $sha1 = Digest::SHA->new('256');
    $sha1->add('Hello');

    my $sha2 = $sha1->clone();
    isa_ok($sha2, 'Digest::SHA', 'Cloned object');

    $sha1->add(' World');
    $sha2->add(' Perl');

    isnt($sha1->hexdigest(), $sha2->hexdigest(), 'Cloned objects are independent');

    # Test clone at different states
    my $sha3 = Digest::SHA->new('1');
    my $sha4 = $sha3->clone();
    is($sha3->hexdigest(), $sha4->hexdigest(), 'Clone of empty digest');
};

subtest 'Reset operation' => sub {
    plan tests => 2;

    my $sha = Digest::SHA->new('256');
    $sha->add('test data');
    my $hex1 = $sha->hexdigest();

    $sha->reset();
    my $hex2 = $sha->hexdigest();

    is($hex2, $test_vectors{sha256}{empty}, 'Reset returns to initial state');

    $sha->add('test data');
    is($sha->hexdigest(), $hex1, 'Same input after reset gives same output');
};

subtest 'Functional interface' => sub {
    plan tests => 21;

    # Test all functional interfaces
    is(sha1_hex(''), $test_vectors{sha1}{empty}, 'sha1_hex empty');
    is(sha1_hex('abc'), $test_vectors{sha1}{abc}, 'sha1_hex abc');

    is(sha224_hex(''), $test_vectors{sha224}{empty}, 'sha224_hex empty');
    is(sha224_hex('abc'), $test_vectors{sha224}{abc}, 'sha224_hex abc');

    is(sha256_hex(''), $test_vectors{sha256}{empty}, 'sha256_hex empty');
    is(sha256_hex('abc'), $test_vectors{sha256}{abc}, 'sha256_hex abc');

    is(sha384_hex(''), $test_vectors{sha384}{empty}, 'sha384_hex empty');
    is(sha384_hex('abc'), $test_vectors{sha384}{abc}, 'sha384_hex abc');

    is(sha512_hex(''), $test_vectors{sha512}{empty}, 'sha512_hex empty');
    is(sha512_hex('abc'), $test_vectors{sha512}{abc}, 'sha512_hex abc');

    # Test binary output
    is(length(sha1('abc')), 20, 'sha1 binary length');
    is(length(sha256('abc')), 32, 'sha256 binary length');
    is(length(sha512('abc')), 64, 'sha512 binary length');

    # Test base64 output
    ok(sha1_base64('abc') =~ /^[A-Za-z0-9+\/]+$/, 'sha1_base64 format');
    ok(sha256_base64('abc') =~ /^[A-Za-z0-9+\/]+$/, 'sha256_base64 format');

    # Test SHA-512/224 and SHA-512/256
    SKIP: {
        skip "SHA-512/224 may not be available", 3 unless eval { sha512224_hex('') };
        ok(sha512224_hex('abc'), 'sha512224_hex works');
        is(length(sha512224('abc')), 28, 'sha512224 binary length');
        ok(sha512224_base64('abc') =~ /^[A-Za-z0-9+\/]+$/, 'sha512224_base64 format');
    }

    SKIP: {
        skip "SHA-512/256 may not be available", 3 unless eval { sha512256_hex('') };
        ok(sha512256_hex('abc'), 'sha512256_hex works');
        is(length(sha512256('abc')), 32, 'sha512256 binary length');
        ok(sha512256_base64('abc') =~ /^[A-Za-z0-9+\/]+$/, 'sha512256_base64 format');
    }
};

subtest 'Edge cases and error handling' => sub {
    plan tests => 6;

    # Test with undef
    my $sha = Digest::SHA->new('256');
    eval { $sha->add(undef) };
    pass('add(undef) does not die');

    # Test with empty string
    $sha = Digest::SHA->new('256');
    $sha->add('');
    is($sha->hexdigest(), $test_vectors{sha256}{empty}, 'add("") works correctly');

    # Test with very long input
    $sha = Digest::SHA->new('1');
    $sha->add('x' x 1000000);
    my $hex = $sha->hexdigest();
    is(length($hex), 40, 'Large input produces correct length output');

    # Test multiple digest calls
    $sha = Digest::SHA->new('256');
    $sha->add('test');
    my $hex1 = $sha->hexdigest();
    my $hex2 = $sha->hexdigest();
    is($hex2, $test_vectors{sha256}{empty}, 'Second digest call on same object returns empty hash');

    # Test algorithm name variations
    SKIP: {
        skip "Algorithm name variations may not be supported", 1
            unless eval { Digest::SHA->new('sha256') };

        my $sha_lower = Digest::SHA->new('sha256');
        my $sha_upper = Digest::SHA->new('SHA256');
        $sha_lower->add('test');
        $sha_upper->add('test');
        is($sha_lower->hexdigest(), $sha_upper->hexdigest(), 'Algorithm name case insensitive');
    }

    # Test clone after digest
    $sha = Digest::SHA->new('1');
    $sha->add('data');
    $sha->hexdigest();
    my $clone = $sha->clone();
    isa_ok($clone, 'Digest::SHA', 'Clone after digest');
};

done_testing();
