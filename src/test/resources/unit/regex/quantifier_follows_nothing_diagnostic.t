use strict;
use warnings;
use Test::More;

for my $pattern ('*a', '(|*)b', '(?i:*a)', '(?i:(|*)b)') {
    my $regex = eval { qr/$pattern/ };
    ok(!defined($regex), "$pattern is rejected");
    like($@, qr/^Quantifier follows nothing/,
        "$pattern uses Perl leading-quantifier diagnostic");
}

done_testing;
