use strict;
use warnings;
use Test::More tests => 1;
use File::Temp qw(tempfile);

my ($fh, $file) = tempfile();
print {$fh} "BEGIN { die \"phooey\\n\" }\n";
close $fh;
my $result = qx{$^X $file 2>&1};
like($result,
    qr/^phooey\nBEGIN failed--compilation aborted at \Q$file\E line 1\.\n\z/,
    'a failing BEGIN reports the BEGIN declaration location without a near clause');
