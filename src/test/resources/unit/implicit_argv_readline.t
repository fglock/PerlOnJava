use strict;
use warnings;
use File::Temp qw(tempfile);
use Test::More;

my ($fh, $filename) = tempfile();
print {$fh} "first\nsecond\n";
close $fh;

my $source = do {
    local (@ARGV, $/) = $filename;
    readline;
};

is $source, "first\nsecond\n",
    'argumentless readline slurps the localized @ARGV file';

done_testing;
