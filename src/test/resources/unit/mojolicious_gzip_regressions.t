use strict;
use warnings;

use Test::More;
use Compress::Raw::Zlib qw(WANT_GZIP Z_OK Z_STREAM_END);

my $uncompressed = 'abc' x 1000;
my $compressed   = pack 'H*',
  '1f8b080054878d6a0003edc2411100000c02a0ac6aff0eabb1071ce9a2aaaafe7e0b45b92bb80b0000';

my $inflater = Compress::Raw::Zlib::Inflate->new(WindowBits => WANT_GZIP());
ok $inflater, 'created a gzip inflate stream';

my $first = substr $compressed, 0, 1;
my $status = $inflater->inflate(\$first, my $first_output);
is 0 + $status, Z_OK(), 'a split gzip header needs more input';
is $first, '', 'the first header byte is consumed';
is $first_output, '', 'a partial header produces no output';

my $rest = substr $compressed, 1;
$status = $inflater->inflate(\$rest, my $output);
is 0 + $status, Z_STREAM_END(), 'the complete gzip stream reaches stream end';
is $rest, '', 'the remaining compressed input is consumed';
is $output, $uncompressed, 'raw gzip inflation returns the original bytes';
is $inflater->total_in, length($compressed), 'gzip total_in includes header and trailer bytes';
is $inflater->total_out, length($uncompressed), 'gzip total_out counts uncompressed bytes';

done_testing;
