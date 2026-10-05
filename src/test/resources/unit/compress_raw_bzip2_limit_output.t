use strict;
use warnings;
use Test::More;
use IO::Compress::Bzip2 qw(bzip2);
use Compress::Raw::Bzip2;

my $payload = "\0" x (2 * 1024 * 1024);
my $compressed;
bzip2(\$payload, \$compressed)
    or die "could not create bzip2 input: $IO::Compress::Bzip2::Bzip2Error";

my ($decoder, $status) = Compress::Raw::Bunzip2->new(1, 0, 0, 0, 1);
ok(defined $decoder, 'limited bzip2 decoder is created');

my $output = '';
$decoder->bzinflate(\$compressed, \$output);
cmp_ok(length($output), '<=', 32 * 1024,
    'LimitOutput bounds decompressed output for a highly compressible stream');

done_testing;
