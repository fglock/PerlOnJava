use strict;
use warnings;
use Test::More;

# Test 1: Compiled calls interpreted
{
    my $interpreted = eval 'sub { $_[0] + $_[1] }';
    my $result = $interpreted->(10, 20);
    is($result, 30, "Compiled code calls interpreted subroutine");
}

# Test 2: Interpreted calls compiled
{
    sub compiled_add { $_[0] + $_[1] }
    my $interpreted = eval 'sub { compiled_add($_[0], $_[1]) }';
    my $result = $interpreted->(10, 20);
    is($result, 30, "Interpreted code calls compiled subroutine");
}

# Test 3: Nested calls (compiled → interpreted → compiled)
{
    sub compiled_double { $_[0] * 2 }
    my $interpreted = eval 'sub { compiled_double($_[0]) + 5 }';
    sub compiled_wrapper { $interpreted->($_[0]) + 10 }
    my $result = compiled_wrapper(3);  # (3*2)+5+10 = 21
    is($result, 21, "Nested cross-calling works");
}

# Test 4: Interpreted closure captures from compiled scope
{
    my $x = 10;
    my $interpreted = eval 'sub { $x + $_[0] }';
    is($interpreted->(5), 15, "Interpreted closure captures from compiled scope");
}

# Test 5: Multiple call depth
{
    sub level1 { $_[0] + 1 }
    my $level2 = eval 'sub { level1($_[0]) + 2 }';
    sub level3 { $level2->($_[0]) + 3 }
    my $level4 = eval 'sub { level3($_[0]) + 4 }';
    is($level4->(1), 11, "Deep call stack works (1+1+2+3+4=11)");
}

# Test 6: Interpreted sub returns value correctly
{
    my $interpreted = eval 'sub { return $_[0] * 10 }';
    is($interpreted->(5), 50, "Interpreted sub returns value correctly");
}

done_testing();
