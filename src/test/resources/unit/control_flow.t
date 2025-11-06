#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# Test suite for tagged return control flow (last/next/redo/goto)
# Tests both local (fast GOTO) and non-local (tagged return) control flow

subtest 'local last - unlabeled' => sub {
    my @output;
    for my $i (1..5) {
        push @output, $i;
        last if $i == 3;
    }
    is_deeply(\@output, [1, 2, 3], 'unlabeled last exits innermost loop');
};

subtest 'local last - labeled' => sub {
    my @output;
    OUTER: for my $i (1..3) {
        for my $j (1..3) {
            push @output, "$i,$j";
            last OUTER if $i == 2 && $j == 2;
        }
    }
    is_deeply(\@output, ['1,1', '1,2', '1,3', '2,1', '2,2'], 
              'labeled last exits correct loop');
};

subtest 'local next - unlabeled' => sub {
    my @output;
    for my $i (1..5) {
        next if $i == 3;
        push @output, $i;
    }
    is_deeply(\@output, [1, 2, 4, 5], 'unlabeled next skips iteration');
};

subtest 'local next - labeled' => sub {
    my @output;
    OUTER: for my $i (1..3) {
        for my $j (1..3) {
            push @output, "$i,$j";
            next OUTER if $j == 2;
        }
    }
    is_deeply(\@output, ['1,1', '1,2', '2,1', '2,2', '3,1', '3,2'], 
              'labeled next continues correct loop');
};

subtest 'local redo - unlabeled' => sub {
    my @output;
    my $count = 0;
    for my $i (1..3) {
        $count++;
        push @output, $i;
        redo if $count == 2 && $i == 2;
    }
    is_deeply(\@output, [1, 2, 2, 3], 'unlabeled redo restarts iteration');
};

subtest 'local redo - labeled' => sub {
    my @output;
    my $count = 0;
    OUTER: for my $i (1..2) {
        for my $j (1..2) {
            $count++;
            push @output, "$i,$j";
            redo OUTER if $count == 3;
        }
    }
    # When redo OUTER triggers from inner loop, it restarts outer loop
    # but the outer loop variable ($i) keeps its current value
    is_deeply(\@output, ['1,1', '1,2', '2,1', '2,1', '2,2'], 
              'labeled redo restarts correct loop (keeps loop variable)');
};

subtest 'goto label - forward' => sub {
    my @output;
    push @output, 'before';
    goto SKIP;
    push @output, 'skipped';
    SKIP:
    push @output, 'after';
    is_deeply(\@output, ['before', 'after'], 'goto skips forward');
};

subtest 'goto label - in same loop' => sub {
    my @output;
    for my $i (1..3) {
        push @output, "start-$i";
        goto NEXT if $i == 2;
        push @output, "middle-$i";
        NEXT:
        push @output, "end-$i";
    }
    is_deeply(\@output, ['start-1', 'middle-1', 'end-1', 
                         'start-2', 'end-2', 
                         'start-3', 'middle-3', 'end-3'], 
              'goto label within loop');
};

subtest 'bare block with last' => sub {
    my $result;
    BLOCK: {
        $result = 'started';
        last BLOCK;
        $result = 'should not reach';
    }
    is($result, 'started', 'last exits bare block');
};

subtest 'nested loops - inner last' => sub {
    my @output;
    for my $i (1..3) {
        for my $j (1..3) {
            push @output, "$i,$j";
            last if $j == 2;
        }
    }
    is_deeply(\@output, ['1,1', '1,2', '2,1', '2,2', '3,1', '3,2'], 
              'inner last only exits inner loop');
};

subtest 'nested loops - outer last' => sub {
    my @output;
    OUTER: for my $i (1..3) {
        for my $j (1..3) {
            push @output, "$i,$j";
            last OUTER if $i == 2 && $j == 2;
        }
    }
    is_deeply(\@output, ['1,1', '1,2', '1,3', '2,1', '2,2'], 
              'outer last exits both loops');
};

subtest 'while loop with last' => sub {
    my @output;
    my $i = 0;
    while ($i < 10) {
        $i++;
        push @output, $i;
        last if $i == 3;
    }
    is_deeply(\@output, [1, 2, 3], 'last exits while loop');
};

subtest 'until loop with next' => sub {
    my @output;
    my $i = 0;
    until ($i >= 5) {
        $i++;
        next if $i == 3;
        push @output, $i;
    }
    is_deeply(\@output, [1, 2, 4, 5], 'next skips in until loop');
};

subtest 'do-while with last' => sub {
    # Note: In standard Perl, 'last' in a do-while gives a warning
    # "Exiting subroutine via last" because do-while is not a true loop construct
    # We skip this test as it's not standard behavior
    plan skip_all => 'last in do-while is not standard Perl behavior';
};

subtest 'for C-style with last' => sub {
    my @output;
    for (my $i = 1; $i <= 5; $i++) {
        push @output, $i;
        last if $i == 3;
    }
    is_deeply(\@output, [1, 2, 3], 'last exits C-style for');
};

subtest 'for C-style with next' => sub {
    my @output;
    for (my $i = 1; $i <= 5; $i++) {
        next if $i == 3;
        push @output, $i;
    }
    is_deeply(\@output, [1, 2, 4, 5], 'next skips in C-style for');
};

subtest 'last in expression context' => sub {
    my $result = do {
        for my $i (1..5) {
            last if $i == 3;
        }
    };
    # Perl loops don't return meaningful values
    ok(!defined $result || $result eq '', 'last in expression returns undef');
};

subtest 'nested last with labels' => sub {
    my @output;
    OUTER: for my $i (1..3) {
        INNER: for my $j (1..3) {
            push @output, "$i,$j";
            last INNER if $j == 2;
            last OUTER if $i == 2 && $j == 1;
        }
    }
    is_deeply(\@output, ['1,1', '1,2', '2,1'], 
              'multiple labeled lasts work correctly');
};

subtest 'redo without infinite loop' => sub {
    my @output;
    my $count = 0;
    for my $i (1..2) {
        $count++;
        push @output, "$i-$count";
        if ($count == 1) {
            $count++;
            redo;
        }
    }
    # redo restarts loop body but doesn't reset outer variables
    # count goes: 1, 2 (after redo), 3 (first iter done), 4 (second iter)
    is_deeply(\@output, ['1-1', '1-3', '2-4'], 'redo restarts body but keeps outer variables');
};

subtest 'goto with condition' => sub {
    my @output;
    my $x = 1;
    push @output, 'start';
    goto END if $x;
    push @output, 'middle';
    END:
    push @output, 'end';
    is_deeply(\@output, ['start', 'end'], 'conditional goto works');
};

subtest 'last in conditional' => sub {
    my @output;
    for my $i (1..5) {
        push @output, $i;
        $i == 3 && last;
    }
    is_deeply(\@output, [1, 2, 3], 'last in && expression');
};

subtest 'next in conditional' => sub {
    my @output;
    for my $i (1..5) {
        $i == 3 && next;
        push @output, $i;
    }
    is_deeply(\@output, [1, 2, 4, 5], 'next in && expression');
};

done_testing();

