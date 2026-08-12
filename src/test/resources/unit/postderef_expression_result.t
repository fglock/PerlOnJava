use strict;
use warnings;
use Test::More tests => 3;

sub array_result { [2, 3] }
sub hash_result { { left => 4, right => 5 } }

is_deeply([1, array_result()->@*], [1, 2, 3],
    'postfix array dereference accepts a call expression');
is(scalar(hash_result()->%*), 2,
    'postfix hash dereference accepts a call expression in scalar context');
is_deeply({hash_result()->%*}, {left => 4, right => 5},
    'postfix hash dereference accepts a call expression in list context');
