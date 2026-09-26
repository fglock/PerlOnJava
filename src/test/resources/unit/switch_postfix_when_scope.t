use strict;
use warnings;
use Test::More;
use feature 'switch';

{
    my $x = 'outer';
    given (my $x = 'given') {
        do {
            is($x, 'given', 'postfix when body retains its earlier lexical binding');
            continue;
        } when be_true(my $x = 'condition');
        is($x, 'condition', 'condition lexical is visible after postfix when');
    }
}

{
    given (my $x = 1) {
        my $x = 2, continue when be_true();
        is($x, undef, 'a declaration before postfix continue remains in scope but unassigned');
    }
}

sub be_true { 1 }

{
    eval { continue };
    like($@, qr/^Can't "continue" outside/, 'eval catches continue outside a when block');

    eval { break };
    like($@, qr/^Can't "break" outside/, 'eval catches break outside a given block');
}

done_testing;
