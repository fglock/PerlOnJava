use strict;
use warnings;
use Test::More tests => 2;

BEGIN {
    eval q{
        require experimental;
        experimental->import('bitwise');
    };
    die $@ if $@;
}

is(eval q{'105' |. '010'}, '115', 'feature imported by BEGIN eval affects later compilation');
is(eval q{~. "\x00"}, "\xFF", 'dot bitwise complement compiles with imported feature');
