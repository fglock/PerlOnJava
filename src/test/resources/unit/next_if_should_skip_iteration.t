use strict;
use warnings;
use Test::More;

# Regression test for perl5_t/t/uni/variables.t drift.
# In perl, `next if (...)` must skip the *entire* loop iteration.
# jperl currently mis-handles this in a specific shape, causing +12 extra checks.

my $count = 0;

for my $ord (0x21 .. 0x2f) { # includes '#' (0x23) and '*' (0x2a)
    my $chr = chr $ord;
    my $tests = 0;

    if ($chr =~ /[[:punct:][:digit:]]/a) {
        next if ($chr eq '#' or $chr eq '*');

        $count++;
        $tests++;
        $count++;
        $tests++;
    }

    $count++;
    $tests++;

    SKIP: {
        my $pad = 6 - $tests;
        $count += $pad if $pad > 0;
    }
}

is($count, 78, 'next-if must skip the whole iteration (no +12 drift)');

done_testing();
