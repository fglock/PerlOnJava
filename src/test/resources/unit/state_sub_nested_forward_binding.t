use strict;
use warnings;
use feature 'state';
use Test::More;

{
    state sub outer;
    {
        state sub outer {
            sub outer { 47 }
        }
    }
    is eval { outer() }, 47,
        'ordinary definition inside an uncalled state sub fulfills the outer stub';
    is $@, '', 'outer state sub is defined before its first call';
}

my $run = sub {
    state sub inner;
    {
        state sub inner {
            sub inner { 48 }
        }
    }
    is eval { inner() }, 48,
        'nested definition fulfills the enclosing anonymous sub state stub';
    is $@, '', 'anonymous-sub state binding is callable';
};
$run->();
$run->();
done_testing;
