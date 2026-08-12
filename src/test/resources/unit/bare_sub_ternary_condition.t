use strict;
use warnings;
use Test::More tests => 3;

sub false_predicate { 0 }
sub true_predicate { 1 }

is(false_predicate ? 'left' : 'right', 'right',
    'bare subroutine call can be a false ternary condition');
is(true_predicate ? 'left' : 'right', 'left',
    'bare subroutine call can be a true ternary condition');
is(false_predicate ? '\\' : '/', '/',
    'single-quoted backslash in ternary branch parses correctly');
