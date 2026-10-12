use strict;
use warnings;
use Test::More tests => 3;
use Errno qw(ENOENT);
use File::Temp qw(tempfile);

my ($file, $path) = tempfile();
close $file or die "close tempfile: $!";
unlink $path or die "unlink tempfile: $!";

$! = 0;
is(chmod(0600, $path), 0, 'chmod fails for a nonexistent path');
is(0 + $!, ENOENT, 'failed pathname chmod sets ENOENT');
like "$!", qr/No such file or directory/i,
    'failed pathname chmod sets the OS error message';
