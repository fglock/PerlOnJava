use strict;
use warnings;
use Test::More tests => 6;
use File::Temp qw(tempfile);

use warnings 'layer';

my ($fh, $path) = tempfile();
close $fh or die "close tempfile: $!";
open $fh, '<', $path or die "open tempfile: $!";

my $warning = '';
local $SIG{__WARN__} = sub { $warning .= shift };

$! = 0;
ok(!binmode($fh, ':-)'), 'binmode rejects a malformed PerlIO layer');
ok($! != 0, 'binmode sets errno for an invalid layer');
like($warning, qr/in PerlIO layer/, 'binmode warns with PerlIO layer context');

$warning = '';
$! = 0;
ok(!open(my $bad, '<:-)', $path), 'open rejects a malformed PerlIO layer');
ok($! ne '', 'open sets errno for an invalid layer');
like($warning, qr/in PerlIO layer/, 'open warns with PerlIO layer context');

close $fh;
unlink $path;
