#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

eval {
    require Compress::Raw::Bzip2;
    Compress::Raw::Bzip2->import;
    1;
} or plan skip_all => 'Compress::Raw::Bzip2 required';
plan tests => 16;

SKIP: {
    skip 'PerlOnJava provider version contract', 2 unless $^X =~ /jperl/;
    is($Compress::Raw::Bzip2::VERSION, '2.224', 'advertises the audited 2.224 Perl API');
    is($Compress::Raw::Bzip2::XS_VERSION, '2.224', 'Java XS provider satisfies the 2.224 prerequisite');
}

is(Compress::Raw::Bzip2::BZ_OK(), 0, 'BZ_OK exported');
is(Compress::Raw::Bzip2::BZ_RUN_OK(), 1, 'BZ_RUN_OK exported');
is(Compress::Raw::Bzip2::BZ_STREAM_END(), 4, 'BZ_STREAM_END exported');
like(Compress::Raw::Bzip2::bzlibversion(), qr/^1\./, 'bzlibversion reports bzip2 1.x');

my ($bz, $err) = new Compress::Raw::Bzip2(1);
ok($bz, 'created bzip2 stream');
cmp_ok($err, '==', Compress::Raw::Bzip2::BZ_OK(), 'bzip2 constructor status');

my $compressed = '';
cmp_ok($bz->bzdeflate('hello ', $compressed), '==', Compress::Raw::Bzip2::BZ_RUN_OK(), 'bzdeflate status');
cmp_ok($bz->bzdeflate('world', $compressed), '==', Compress::Raw::Bzip2::BZ_RUN_OK(), 'second bzdeflate status');
cmp_ok($bz->bzclose($compressed), '==', Compress::Raw::Bzip2::BZ_STREAM_END(), 'bzclose status');
is($bz->uncompressedBytes, 11, 'uncompressed byte count');
ok(length($compressed) > 0, 'compressed bytes produced');

my ($bunzip, $bunzip_err) = new Compress::Raw::Bunzip2(1, 1);
cmp_ok($bunzip_err, '==', Compress::Raw::Bzip2::BZ_OK(), 'bunzip constructor status');

$compressed .= 'tail';
my $plain = '';
cmp_ok($bunzip->bzinflate($compressed, $plain), '==', Compress::Raw::Bzip2::BZ_STREAM_END(), 'bzinflate status');
is($plain, 'hello world', 'round trip payload');
