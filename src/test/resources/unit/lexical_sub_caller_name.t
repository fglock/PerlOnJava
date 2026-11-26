use v5.18;
use strict;
use warnings;
use feature qw(lexical_subs state);
no warnings 'experimental::lexical_subs';
use Test::More;

{
    state sub x { is +(caller 0)[3], 'x', 'caller reports a state sub name'; }
    x
}

{
    my sub x { is +(caller 0)[3], 'x', 'caller reports a my sub name'; }
    x
}

done_testing;
