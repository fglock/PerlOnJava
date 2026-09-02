#!/usr/bin/env perl
use strict;
use warnings;

use Test::More;
use Compress::Bzip2 qw();

my $payload = "bzip2 streaming inflater payload\n";
my $compressor = Compress::Bzip2::bzdeflateInit();
my $compressed = $compressor->bzdeflate($payload) . $compressor->bzclose;
my $inflater = Compress::Bzip2::inflateInit();
ok($inflater, 'inflateInit creates a Compress::Zlib-compatible bzip2 inflater');

my $output = $inflater->bzinflate(\$compressed);
is($output, $payload, 'bzinflate returns the decompressed payload');

done_testing;
