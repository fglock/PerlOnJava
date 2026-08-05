use strict;
use warnings;
use IO::File;
use Test::More;

my $fh = IO::File->new_tmpfile;
ok($fh, 'created an anonymous temporary file');
ok(-z $fh, 'new temporary file is empty');
is(-s $fh, 0, 'empty temporary file has size zero');

$fh->print("foo\n");
$fh->flush;
is(-s $fh, 4, 'filehandle size sees writes to an anonymous temporary file');
ok(!-z $fh, 'temporary file is no longer empty');

done_testing;
