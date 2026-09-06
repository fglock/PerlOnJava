use strict;
use warnings;
use Test::More;

sub nested_branch_result {
    if ($_[0]) {
        { 5 }
    }
    else {
        { 6 }
    }
}

is(nested_branch_result(1), 5, 'nested true branch returns its final value');
is(nested_branch_result(0), 6, 'nested false branch returns its final value');

done_testing;
