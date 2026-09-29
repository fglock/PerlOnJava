use strict;
use warnings;
use Test::More tests => 1;
use File::Temp qw(tempfile);

my ($fh, $file) = tempfile();
print {$fh} "if (\$x == 0) {}\n";
close $fh;

my $output = qx{$^X -w $file 2>&1};
like($output, qr/Use of uninitialized value \$x in numeric eq \(==\)/,
    'the -w flag warns for an uninitialized numeric comparison in a file');
