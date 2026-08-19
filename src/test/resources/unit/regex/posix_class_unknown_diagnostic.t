use strict;
use warnings;
use Test::More;

for my $name ('foo', '^foo', 'xyz') {
    my $pattern = "[[:$name:]]";
    my $regex = eval { qr/$pattern/ };
    ok(!defined($regex), "$pattern is rejected");
    like($@, qr/^POSIX class \[:\Q$name\E:\] unknown/,
        "$pattern uses Perl POSIX-class diagnostic");
}

done_testing;
