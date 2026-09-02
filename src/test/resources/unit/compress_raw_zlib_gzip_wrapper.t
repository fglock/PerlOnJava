#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;
use Compress::Raw::Zlib qw(WANT_GZIP Z_STREAM_END);

my $payload = "gzip wrapper payload\n";
my $deflater = Compress::Raw::Zlib::Deflate->new(
    -WindowBits => WANT_GZIP(),
    -AppendOutput => 1,
);
my $wire = '';
$deflater->deflate($payload, $wire);
$deflater->flush($wire);

is(substr($wire, 0, 3), "\x1f\x8b\x08",
    'WANT_GZIP emits an RFC 1952 gzip header');

my $inflater = Compress::Raw::Zlib::Inflate->new(
    -ConsumeInput => 0,
    -WindowBits => WANT_GZIP(),
);
my $output = '';
my $status = $inflater->inflate($wire, $output);

is(0 + $status, 0 + Z_STREAM_END(), 'gzip stream reaches end');
is($output, $payload, 'gzip stream round-trips through raw zlib API');

done_testing;
