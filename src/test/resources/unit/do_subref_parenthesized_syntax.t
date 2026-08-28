use strict;
use warnings;
use Test::More;

my $subref = sub { die 'do must not call a parenthesized code reference' };

for my $source ('do $subref("arg")', 'do $subref()') {
    my $ok = eval $source;
    ok(!defined($ok), "$source does not execute the code reference");
    like($@, qr/^syntax error/, "$source is a syntax error");
}

done_testing();
