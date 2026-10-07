use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);
use Archive::Zip qw(COMPRESSION_STORED COMPRESSION_DEFLATED AZ_OK);

my ($fh, $path) = tempfile(SUFFIX => '.zip');
close $fh;

my $zip = Archive::Zip->new;
my $member = $zip->addString('some CSV output', 'output.csv');
is($member->compressionMethod, COMPRESSION_STORED,
   'new string member is stored before the archive is written');
is($member->desiredCompressionMethod, COMPRESSION_STORED,
   'new string member defaults to stored when written');
is($member->desiredCompressionMethod(COMPRESSION_DEFLATED), COMPRESSION_STORED,
   'setter returns the previous desired method');
is($member->desiredCompressionMethod, COMPRESSION_DEFLATED,
   'getter returns the desired method');
is($zip->writeToFileNamed($path), AZ_OK, 'writes archive with the requested method');

my $read_zip = Archive::Zip->new;
is($read_zip->read($path), AZ_OK, 'reads the written archive');
is($read_zip->memberNamed('output.csv')->compressionMethod, COMPRESSION_DEFLATED,
   'archive member uses the requested deflated method');

unlink $path;
done_testing;
