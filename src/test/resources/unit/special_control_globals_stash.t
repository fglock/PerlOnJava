use strict;
use warnings;
use Test::More;

my @absent  = qw(B G J K Q U Y Z);
my %stash_keys = map { $_ => 1 } keys %main::;

for my $letter (@absent) {
    my $key = chr(ord($letter) - ord('A') + 1);
    ok(!$stash_keys{$key}, "non-special \$^$letter is absent from main stash enumeration");
}

done_testing();
