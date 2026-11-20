use strict;
use warnings;
use Test::More;

# Test 1: Simple closure
{
    my $x = 10;
    my $closure = eval 'sub { $x + $_[0] }';
    is($closure->(5), 15, "Simple closure captures \$x");
}

# Test 2: Closure modifies captured variable
{
    my $counter = 0;
    my $increment = eval 'sub { $counter++ }';
    $increment->();
    $increment->();
    is($counter, 2, "Closure can modify captured variable");
}

# Test 3: Multiple captured variables
{
    my $x = 10;
    my $y = 20;
    my $closure = eval 'sub { $x + $y + $_[0] }';
    is($closure->(5), 35, "Closure captures multiple variables");
}

# Test 4: Closure with no captures (control test)
{
    my $closure = eval 'sub { $_[0] + $_[1] }';
    is($closure->(10, 20), 30, "Closure with no captures works");
}

# Test 5: Closure captures global $_ (should use global, not capture)
{
    $_ = 42;
    my $closure = eval 'sub { $_ + $_[0] }';
    is($closure->(8), 50, "Closure uses global \$_");
}

done_testing();
