use strict;
use warnings;
use Test::More tests => 5;
use File::Temp qw(tempfile);
use Compress::Zlib;

my ($fh, $file) = tempfile(SUFFIX => '.gz');
close $fh;

my $writer = gzopen($file, 'wb');
ok($writer, 'opened gzip writer');
is($writer->gzwrite('payload'), 7, 'wrote payload');
is($writer->gzclose, Z_OK, 'closed gzip writer');

my $reader = gzopen($file, 'rb');
my $buffer = '';
is($reader->gzread($buffer, 4096), 7, 'read through end of gzip stream');
is(0 + $reader->gzerror, Z_STREAM_END, 'gzerror reports successful stream end');
