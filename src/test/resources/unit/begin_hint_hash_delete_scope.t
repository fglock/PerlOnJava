use strict;
use warnings;
use Test::More tests => 2;

sub caller_hint {
    my @caller = caller(0);
    return @caller > 10 ? $caller[10]{unit_test_hint} : undef;
}

BEGIN { $^H{unit_test_hint} = 42 }

{
    BEGIN { delete $^H{unit_test_hint} }
    is(caller_hint(), undef, 'BEGIN deletion is visible in the enclosing scope');
}

is(caller_hint(), 42, 'BEGIN deletion restores the outer lexical hint');
