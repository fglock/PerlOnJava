use strict;
use warnings;
use feature 'try';
no warnings 'experimental::try';
use Test::More tests => 2;

our $goto_result = '';

sub goto_finally_flat {
    try { }
    catch ($error) { }
    finally {
        goto local_target;
        $goto_result .= "wrong\n";
        local_target:
        $goto_result .= "flat\n";
    }
    $goto_result .= "after\n";
}

goto_finally_flat();
is($goto_result, "flat\nafter\n",
    'goto to a label in the same finally block is allowed');

$goto_result = "start\n";
sub goto_finally_nested {
    try { }
    catch ($error) { }
    finally {
        if ($goto_result) {
            $goto_result .= "before\n";
            goto nested_target;
            $goto_result .= "wrong\n";
        } else {
            nested_target:
            $goto_result .= "nested\n";
        }
    }
    $goto_result .= "after\n";
}

goto_finally_nested();
is($goto_result, "start\nbefore\nnested\nafter\n",
    'goto can target a nested branch label in the same finally block');
