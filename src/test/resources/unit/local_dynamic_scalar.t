use strict;
use warnings;
use Test::More;

our $target = 'outer';
our @target = ('array slot');
my $name = 'target';

{
    no strict 'refs';
    local $$name = 'inner';
    is($target, 'inner', 'local $$name localizes the named scalar');
    is_deeply(\@target, ['array slot'], 'local $$name leaves sibling glob slots intact');
}

is($target, 'outer', 'dynamic scalar localization restores the outer value');

{
    no strict 'refs';
    ${$name}[1] = 'dynamic array element';
    is($target[1], 'dynamic array element', 'dynamic scalar name can address an array element');
}

done_testing;
