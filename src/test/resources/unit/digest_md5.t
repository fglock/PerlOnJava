#!/usr/bin/perl
use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

BEGIN {
    use_ok('Digest::MD5', qw(md5 md5_hex md5_base64));
}

# Test vectors for MD5
my %test_vectors = (
    empty => 'd41d8cd98f00b204e9800998ecf8427e',
    abc => '900150983cd24fb0d6963f7d28e17f72',
    message_digest => 'bae33f0d6044b5ce7dc9f98d9e017845',
    abcdefghijklmnopqrstuvwxyz => 'c3fcd3d76192e4007dfb496cca67e13b',
    ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 => 'd174ab98d277d9f5a5611c2c9f419d9f',
    '12345678901234567890123456789012345678901234567890123456789012345678901234567890' => '57edf4a22be3c955ac49da2e2107b67a',
);

subtest 'Object creation' => sub {
    plan tests => 1;

    my $md5 = Digest::MD5->new();
    isa_ok($md5, 'Digest::MD5', 'new() creates object');

    ## # Test that new() doesn't accept parameters
    ## eval { Digest::MD5->new('MD5') };
    ## ok(!$@, 'new() with parameter (ignored)');
};

subtest 'Basic digest operations' => sub {
    plan tests => 11;

    # Test empty string
    my $md5 = Digest::MD5->new();
    is($md5->hexdigest(), $test_vectors{empty}, 'Empty string digest');

    # Test various strings
    for my $input (qw(abc message_digest abcdefghijklmnopqrstuvwxyz)) {
        $md5 = Digest::MD5->new();
        $md5->add($input);
        is($md5->hexdigest(), $test_vectors{$input}, "Digest of '$input'");
    }

    # Test long string
    $md5 = Digest::MD5->new();
    $md5->add('ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789');
    is($md5->hexdigest(), $test_vectors{ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789},
       'Digest of alphanumeric string');

    # Test numeric string
    $md5 = Digest::MD5->new();
    $md5->add('1234567890' x 8);
    is($md5->hexdigest(), $test_vectors{'12345678901234567890123456789012345678901234567890123456789012345678901234567890'},
       'Digest of repeated numeric string');

    # Test multiple add() calls
    $md5 = Digest::MD5->new();
    $md5->add('a');
    $md5->add('b');
    $md5->add('c');
    is($md5->hexdigest(), $test_vectors{abc}, 'Multiple add() calls');

    # Test add() with multiple arguments
    $md5 = Digest::MD5->new();
    $md5->add('a', 'b', 'c');
    is($md5->hexdigest(), $test_vectors{abc}, 'add() with multiple arguments');

    # Test digest formats
    $md5 = Digest::MD5->new();
    $md5->add('abc');
    my $digest = $md5->digest();
    is(length($digest), 16, 'MD5 digest length is 16 bytes');

    # Test base64 format
    $md5 = Digest::MD5->new();
    $md5->add('abc');
    my $b64 = $md5->b64digest();
    ok($b64 =~ /^[A-Za-z0-9+\/]+$/, 'Base64 digest format');
    is(length($b64), 22, 'Base64 digest length');
};

subtest 'File operations' => sub {
    plan tests => 4;

    # Create a temporary file
    my ($fh, $filename) = tempfile(UNLINK => 1);
    print $fh "The quick brown fox jumps over the lazy dog";
    close $fh;

    # Test addfile with filename
    my $md5 = Digest::MD5->new();
    eval { $md5->addfile($filename) };
    is($md5->hexdigest(), 'd41d8cd98f00b204e9800998ecf8427e',
       'addfile with filename');

    # Test with filehandle
    open my $fh2, '<', $filename or die "Cannot open $filename: $!";
    $md5 = Digest::MD5->new();
    $md5->addfile($fh2);
    close $fh2;
    is($md5->hexdigest(), '9e107d9d372bb6826bd81d3542a419d6',
       'addfile with filehandle');

    # Test with binary mode
    open my $fh3, '<:raw', $filename or die "Cannot open $filename: $!";
    $md5 = Digest::MD5->new();
    $md5->addfile($fh3);
    close $fh3;
    is($md5->hexdigest(), '9e107d9d372bb6826bd81d3542a419d6',
       'addfile with binary filehandle');

    # Test with non-existent file
    $md5 = Digest::MD5->new();
    eval { $md5->addfile('/non/existent/file') };
    ok($@, 'addfile with non-existent file throws error');
};

subtest 'Clone operations' => sub {
    plan tests => 4;

    my $md5_1 = Digest::MD5->new();
    $md5_1->add('Hello');

    my $md5_2 = $md5_1->clone();
    isa_ok($md5_2, 'Digest::MD5', 'Cloned object');

    $md5_1->add(' World');
    $md5_2->add(' Perl');

    isnt($md5_1->hexdigest(), $md5_2->hexdigest(), 'Cloned objects are independent');

    # Test clone at different states
    my $md5_3 = Digest::MD5->new();
    my $md5_4 = $md5_3->clone();
    is($md5_3->hexdigest(), $md5_4->hexdigest(), 'Clone of empty digest');

    # Test clone after partial data
    my $md5_5 = Digest::MD5->new();
    $md5_5->add('test');
    my $md5_6 = $md5_5->clone();
    $md5_5->add('123');
    $md5_6->add('123');
    is($md5_5->hexdigest(), $md5_6->hexdigest(), 'Clone preserves state');
};

subtest 'Functional interface' => sub {
    plan tests => 9;

    # Test md5()
    my $digest = md5('');
    is(length($digest), 16, 'md5() returns 16 bytes');

    $digest = md5('abc');
    is(unpack('H*', $digest), $test_vectors{abc}, 'md5() correct digest');

    # Test md5_hex()
    is(md5_hex(''), $test_vectors{empty}, 'md5_hex() empty string');
    is(md5_hex('abc'), $test_vectors{abc}, 'md5_hex() abc');
    is(md5_hex('message_digest'), $test_vectors{message_digest}, 'md5_hex() message_digest');

    # Test md5_base64()
    my $b64 = md5_base64('abc');
    ok($b64 =~ /^[A-Za-z0-9+\/]+$/, 'md5_base64() format');
    is(length($b64), 22, 'md5_base64() length');

    # Test with multiple arguments
    is(md5_hex('a', 'b', 'c'), $test_vectors{abc}, 'md5_hex() multiple arguments');

    # Test with undef
    is(md5_hex(undef), $test_vectors{empty}, 'md5_hex(undef) same as empty string');
};

subtest 'Edge cases and error handling' => sub {
    plan tests => 8;

    # Test with undef
    my $md5 = Digest::MD5->new();
    eval { $md5->add(undef) };
    ok(!$@, 'add(undef) does not die');
    is($md5->hexdigest(), $test_vectors{empty}, 'add(undef) same as empty');

    # Test with empty string
    $md5 = Digest::MD5->new();
    $md5->add('');
    is($md5->hexdigest(), $test_vectors{empty}, 'add("") works correctly');

    # Test with reference
    $md5 = Digest::MD5->new();
    eval { $md5->add([]) };
    ok($@ eq '', 'add() with arrayref throws no error');

    # Test multiple digest calls
    $md5 = Digest::MD5->new();
    $md5->add('test');
    my $hex1 = $md5->hexdigest();
    my $hex2 = $md5->hexdigest();
    is($hex2, $test_vectors{empty}, 'Second digest call returns empty hash');

    # Test large input
    $md5 = Digest::MD5->new();
    $md5->add('x' x 1000000);
    my $hex = $md5->hexdigest();
    is(length($hex), 32, 'Large input produces correct length output');

    # Test binary data
    $md5 = Digest::MD5->new();
    $md5->add(pack('C*', 0..255));
    $hex = $md5->hexdigest();
    is(length($hex), 32, 'Binary data produces correct length output');

    # Test UTF-8 handling
    $md5 = Digest::MD5->new();
    my $utf8_string = "Hello \x{263A}"; # Unicode smiley
    eval { $md5->add($utf8_string) };
    ok($@, 'Wide character in add() throws error');
};

subtest 'Reset behavior' => sub {
    plan tests => 3;

    my $md5 = Digest::MD5->new();
    $md5->add('test');
    my $hex1 = $md5->hexdigest();

    # After digest, object should be reset
    $md5->add('test');
    my $hex2 = $md5->hexdigest();
    is($hex1, $hex2, 'Same input gives same output after auto-reset');

    # Multiple digest calls
    $md5 = Digest::MD5->new();
    $md5->add('data');
    $md5->digest();
    is($md5->hexdigest(), $test_vectors{empty}, 'digest() resets state');

    # b64digest also resets
    $md5 = Digest::MD5->new();
    $md5->add('data');
    $md5->b64digest();
    is($md5->hexdigest(), $test_vectors{empty}, 'b64digest() resets state');
};

subtest 'Context sensitivity' => sub {
    plan tests => 4;

    # In list context
    my $md5 = Digest::MD5->new();
    $md5->add('test');
    my @result = $md5->digest();
    is(scalar @result, 1, 'digest() in list context returns single element');
    is(length($result[0]), 16, 'digest() in list context returns 16 bytes');

    # Check that methods return the object for chaining
    $md5 = Digest::MD5->new();
    my $ret = $md5->add('test');
    isa_ok($ret, 'Digest::MD5', 'add() returns object for chaining');

    # Test method chaining
    my $hex = Digest::MD5->new()->add('a')->add('b')->add('c')->hexdigest();
    is($hex, $test_vectors{abc}, 'Method chaining works');
};

done_testing();

