#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;
use Compress::Bzip2 qw();

my $payload = "bzip2 streaming payload\n";
my $compressor = Compress::Bzip2::bzdeflateInit();
ok($compressor, 'bzdeflateInit creates a streaming compressor');

my $compressed = $compressor->bzdeflate($payload);
$compressed .= $compressor->bzclose;

ok(length($compressed), 'streaming compressor returns bzip2 bytes at close');
is(Compress::Bzip2::memBunzip($compressed), $payload,
    'streaming bzip2 output round-trips through memBunzip');

done_testing;
