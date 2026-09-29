use strict;
use warnings;
use Test::More tests => 1;
use File::Temp qw(tempfile);

my ($fh, $file) = tempfile();
print {$fh} <<'PERL';
BEGIN {
  $SIG{__WARN__} = sub {};
  die "x\n";
}
PERL
close $fh;

my $result = qx{$^X $file 2>&1};
like($result,
    qr/^x\nBEGIN failed--compilation aborted at \Q$file\E line 4\.\n\z/,
    'BEGIN failures after installing a warning handler point at the closing brace');
