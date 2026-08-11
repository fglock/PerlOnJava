use 5.34.0;
use strict;
use warnings;
use feature 'try';
use Test::More;

sub return_from_try {
    try {
        return 'try result';
    }
    catch ($error) {
        return 'wrong catch';
    }
    return 'continued after try';
}

sub return_from_catch {
    try {
        die "expected failure\n";
    }
    catch ($error) {
        return "caught: $error";
    }
    return 'continued after catch';
}

is(return_from_try(), 'try result',
    'return in try exits the containing subroutine');
is(return_from_catch(), "caught: expected failure\n",
    'return in catch exits the containing subroutine');

done_testing;
