use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More tests => 5;

my ($fh, $path) = tempfile(UNLINK => 1);
binmode $fh, ':encoding(UTF-8)';
print {$fh} "éx" or die "write: $!";

is(tell($fh), 5, 'tell reports encoded byte position');
ok(seek($fh, 2, 0), 'seek to an encoded byte position succeeds');
is(tell($fh), 2, 'tell reports the seeked encoded position');
is(unpack('H*', <$fh> // ''), 'a978', 'read after multibyte seek is stable');
ok(truncate($fh, 1), 'truncate on an encoded handle succeeds');
