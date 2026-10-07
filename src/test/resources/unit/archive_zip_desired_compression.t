use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);
use Archive::Zip qw(COMPRESSION_STORED COMPRESSION_DEFLATED AZ_OK);

my ($fh, $path) = tempfile(SUFFIX => '.zip');
close $fh;
my $payload = 'zip compression level ' x 1000;

my $zip = Archive::Zip->new;
my $member = $zip->addString($payload, 'output.csv');
is($member->compressionMethod, COMPRESSION_STORED,
   'new string member is stored before the archive is written');
is($member->desiredCompressionMethod, COMPRESSION_STORED,
   'new string member defaults to stored when written');
is($member->desiredCompressionLevel, 0,
   'new stored member defaults to no compression');
is($member->desiredCompressionMethod(COMPRESSION_DEFLATED), COMPRESSION_STORED,
   'setter returns the previous desired method');
is($member->desiredCompressionMethod, COMPRESSION_DEFLATED,
   'getter returns the desired method');
is($member->desiredCompressionLevel, -1,
   'switching to deflated selects the zlib default level');
is($member->desiredCompressionLevel(1), -1,
   'compression level setter returns the previous level');
is($member->desiredCompressionLevel, 1,
   'getter returns the requested compression level');
is($member->desiredCompressionMethod, COMPRESSION_DEFLATED,
   'nonzero compression level selects deflated compression');
my $best_member = $zip->addString($payload, 'best.csv');
$best_member->desiredCompressionMethod(COMPRESSION_DEFLATED);
is($best_member->desiredCompressionLevel(9), -1,
   'a second member can select a different compression level');
is($zip->writeToFileNamed($path), AZ_OK, 'writes archive with the requested method');
is($member->desiredCompressionLevel(0), 1,
   'zero compression level setter returns the previous level');
is($member->desiredCompressionMethod, COMPRESSION_STORED,
   'zero compression level selects stored compression');

my $read_zip = Archive::Zip->new;
is($read_zip->read($path), AZ_OK, 'reads the written archive');
is($read_zip->memberNamed('output.csv')->compressionMethod, COMPRESSION_DEFLATED,
   'archive member uses the requested deflated method');
my $level_one_size = $read_zip->memberNamed('output.csv')->compressedSize;
is($read_zip->memberNamed('best.csv')->compressionMethod, COMPRESSION_DEFLATED,
   'second archive member is deflated');
cmp_ok($read_zip->memberNamed('best.csv')->compressedSize, '<', $level_one_size,
   'each ZIP member uses its own compression level');

unlink $path;
done_testing;
