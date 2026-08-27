use strict;
use warnings;
use Test::More;

BEGIN {
    my $value = 'truthy';
    *DynamicConstant::VALUE = sub () { $value };
}

sub result_from_compile_time_constant {
    return DynamicConstant::VALUE() ? 'inlined' : 'runtime';
}

{
    no warnings 'redefine';
    *DynamicConstant::VALUE = sub { 0 };
}

my $name = 'DynamicConstant::VALUE';
no strict 'refs';
is(&{$name}(), 0, 'the constant subroutine was replaced at runtime');
is(result_from_compile_time_constant(), 'inlined',
    'a constant installed during BEGIN is inlined into later code');

done_testing;
