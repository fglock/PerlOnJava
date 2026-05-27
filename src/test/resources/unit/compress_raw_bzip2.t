#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 14;

use Compress::Raw::Bzip2;

is(BZ_OK, 0, 'BZ_OK exported');
is(BZ_RUN_OK, 1, 'BZ_RUN_OK exported');
is(BZ_STREAM_END, 4, 'BZ_STREAM_END exported');
like(Compress::Raw::Bzip2::bzlibversion(), qr/^1\./, 'bzlibversion reports bzip2 1.x');

my ($bz, $err) = new Compress::Raw::Bzip2(1);
ok($bz, 'created bzip2 stream');
is($err, BZ_OK, 'bzip2 constructor status');

my $compressed = '';
is($bz->bzdeflate('hello ', $compressed), BZ_RUN_OK, 'bzdeflate status');
is($bz->bzdeflate('world', $compressed), BZ_RUN_OK, 'second bzdeflate status');
is($bz->bzclose($compressed), BZ_STREAM_END, 'bzclose status');
is($bz->uncompressedBytes, 11, 'uncompressed byte count');
ok(length($compressed) > 0, 'compressed bytes produced');

my ($bunzip, $bunzip_err) = new Compress::Raw::Bunzip2(1, 1);
is($bunzip_err, BZ_OK, 'bunzip constructor status');

$compressed .= 'tail';
my $plain = '';
is($bunzip->bzinflate($compressed, $plain), BZ_STREAM_END, 'bzinflate status');
is($plain, 'hello world', 'round trip payload');
