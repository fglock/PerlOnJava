#!/usr/bin/perl

use strict;
use warnings;
use Test::More;

BEGIN {
    eval {
        require Crypt::PRNG;
        Crypt::PRNG->import(qw(
            random_bytes random_bytes_hex random_bytes_b64 random_bytes_b64u
            random_string random_string_from rand irand
        ));
        1;
    } or plan skip_all => 'Crypt::PRNG is not installed in this Perl';
}

my $prng = Crypt::PRNG->new;
ok($prng, 'new returns an object');

is(length($prng->bytes(16)), 16, 'bytes length');
like($prng->bytes_hex(8), qr/\A[0-9a-f]{16}\z/, 'bytes_hex shape');
is(length($prng->bytes_b64(6)), 8, 'bytes_b64 length');
like($prng->bytes_b64u(6), qr/\A[A-Za-z0-9_-]{8}\z/, 'bytes_b64u shape');
like($prng->string(24), qr/\A[A-Za-z0-9]{24}\z/, 'string shape');
like($prng->string_from('ABC', 24), qr/\A[ABC]{24}\z/, 'string_from shape');

my $n = $prng->double(10);
ok($n >= 0 && $n < 10, 'double range');

my $i = $prng->int32;
ok($i >= 0 && $i <= 0xFFFFFFFF, 'int32 range');

is(length(random_bytes(12)), 12, 'random_bytes function');
like(random_bytes_hex(4), qr/\A[0-9a-f]{8}\z/, 'random_bytes_hex function');
is(length(random_bytes_b64(6)), 8, 'random_bytes_b64 function');
like(random_bytes_b64u(6), qr/\A[A-Za-z0-9_-]{8}\z/, 'random_bytes_b64u function');
like(random_string(12), qr/\A[A-Za-z0-9]{12}\z/, 'random_string function');
like(random_string_from('xy', 12), qr/\A[xy]{12}\z/, 'random_string_from function');

ok(rand(5) >= 0 && rand(5) < 5, 'rand function');
ok(irand() >= 0 && irand() <= 0xFFFFFFFF, 'irand function');

eval { $prng->bytes(-1) };
like($@, qr/\AFATAL: output_len too large/, 'negative length croaks');

eval { Crypt::PRNG->new('RC4', 'abcd') };
like($@, qr/\AFATAL: PRNG_ready failed:/, 'short RC4 seed croaks');

done_testing;
