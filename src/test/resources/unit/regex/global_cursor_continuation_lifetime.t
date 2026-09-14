use strict;
use warnings;
use Test::More;

my $subject = 'a1b2';
my $other = 'z9';

sub nested_match {
    $other =~ /([a-z])(\d)/g;
    return "$1$2";
}

for my $round (1 .. 2) {
    pos($subject) = 0;
    my $count = 0;
    while ($subject =~ /([a-z])(\d)/g) {
        ++$count;
        if ($count == 1 && $round == 1) {
            is("$1$2", 'a1', 'first global match publishes captures');
            is(nested_match(), 'z9', 'nested regex has its own dynamic match state');
            is("$1$2", 'a1', 'nested regex restores the outer cursor captures');
        }
        is("$1$2", $count == 1 ? 'a1' : 'b2',
            "round $round publishes capture $count before loop scope exits");
        last if $count == 2;
    }
    is($count, 2, "global call site resumes through both matches in round $round");
}

done_testing;
