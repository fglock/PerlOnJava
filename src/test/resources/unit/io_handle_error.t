use strict;
use warnings;
use Test::More;
use IO::File;

$! = 0;
ok(!-f '?', 'missing path probe leaves a false file test result');
ok($!, 'file test set errno for the missing path');

my $fh = IO::File::new_tmpfile();
isa_ok($fh, 'IO::File');
ok(!$fh->error, 'successful handle has no stream error despite stale errno');

done_testing();
