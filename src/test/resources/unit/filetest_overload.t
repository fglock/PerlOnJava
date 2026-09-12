use strict;
use warnings;
use Test::More;

{
    package FileTestOverload;
    use overload
        -X => sub { "-$_[1]" },
        fallback => 1;
}

my $object = bless [], 'FileTestOverload';

is(-f $object, '-f', 'file test dispatches the shared -X overload');
is(-r -f $object, '-r', 'outer stacked file test keeps overloaded subject');
is(-f -r $object, '-f', 'inner stacked file test keeps overloaded subject');

done_testing;
