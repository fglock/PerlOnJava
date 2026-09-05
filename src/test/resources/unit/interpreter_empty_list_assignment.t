use strict;
use warnings;
use Test::More tests => 4;

# `+()` preserves list-assignment syntax while making the assignment expression
# scalar.  An eval error produces an empty RHS list, whose assignment count is 0.
is +() = eval '++', 0, 'empty list assignment counts a syntax-error eval result';
is +() = eval 'die', 0, 'empty list assignment counts a runtime-error eval result';

is +() = (1, 2), 2, 'empty list assignment counts ordinary RHS values';
is +() = (), 0, 'empty list assignment counts an empty RHS';
