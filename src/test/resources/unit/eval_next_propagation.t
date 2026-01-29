use strict;
use warnings;
use Test::More;

# This test demonstrates Perl semantics for control flow from eval STRING.
# It is kept in dev/sandbox until PerlOnJava matches perl.

sub run_eval_next_in_loop {
    my $after_eval = 0;
    for my $i (1..3) {
        local $@;
        eval q{ next; };
        $after_eval++;
    }
    return ($after_eval, $@);
}

sub run_eval_next_in_labeled_block {
    my $after_eval = 0;
    for my $i (1..2) {
        local $@;
        BLOCK: {
            eval q{ next BLOCK; };
            $after_eval++;
        }
    }
    return ($after_eval, $@);
}

my ($after_loop, $err_loop) = run_eval_next_in_loop();
is($after_loop, 0, q{eval 'next' inside a loop should skip the rest of the iteration});
is($err_loop, '', q{$@ should be empty after eval 'next' inside loop});

my ($after_block, $err_block) = run_eval_next_in_labeled_block();
is($after_block, 0, q{eval 'next LABEL' should exit the labeled block});
is($err_block, '', q{$@ should be empty after eval 'next LABEL'});

done_testing();
