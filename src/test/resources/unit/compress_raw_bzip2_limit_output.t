use strict;
use warnings;
use Test::More;
use Compress::Raw::Bzip2;

my $payload = "\0" x (2 * 1024 * 1024);
my ($encoder, $encode_status) = Compress::Raw::Bzip2->new(1);
ok(defined $encoder, 'bzip2 encoder is created');

my $compressed;
$compressed = '';
$encoder->bzdeflate($payload, $compressed);
$encoder->bzclose($compressed);

my ($decoder, $status) = Compress::Raw::Bunzip2->new(1, 0, 0, 0, 1);
ok(defined $decoder, 'limited bzip2 decoder is created');

my $output = '';
$decoder->bzinflate(\$compressed, \$output);
cmp_ok(length($output), '<=', 32 * 1024,
    'LimitOutput bounds decompressed output for a highly compressible stream');

done_testing;
