use strict;
use warnings;
use Test::More;

for my $pattern ('[b-a]', '(?i:a[b-a])', '[\x{100}-\x{ff}]') {
    my $regex = eval { qr/$pattern/ };
    ok(!defined($regex), "$pattern is rejected");
    like($@, qr/^Invalid \[\] range/, "$pattern uses Perl range diagnostic");
}

done_testing;
