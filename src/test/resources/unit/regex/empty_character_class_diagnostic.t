use strict;
use warnings;
use Test::More;

for my $pattern ('a[]b', '(?i:a[]b)') {
    my $regex = eval { qr/$pattern/ };
    ok(!defined($regex), "$pattern is rejected");
    like($@, qr/^Unmatched \[/, "$pattern uses Perl unmatched-class diagnostic");
}

done_testing;
