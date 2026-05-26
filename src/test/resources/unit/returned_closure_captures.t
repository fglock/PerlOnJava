use strict;
use warnings;
use Test::More;

my $destroyed = 0;

{
    package ReturnedClosureIndicator;
    sub DESTROY { $destroyed++ }
}

my $closure;

{
    my $number = bless \(my $slot), 'ReturnedClosureIndicator';
    $$number = 40;

    my $maker = eval 'sub { my $xxx = shift; sub { $$xxx += 2 } }';
    die $@ if $@;

    $closure = $maker->($number);
    $closure->();

    is($$number, 42, 'returned closure can use its captured value');
    is($destroyed, 0, 'captured value is alive before caller scope exits');
}

is($destroyed, 0, 'captured value is alive while returned closure is alive');

undef $closure;
is($destroyed, 1, 'captured value is destroyed after returned closure is released');

done_testing;
