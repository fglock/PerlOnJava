use strict;
use Test::More;
use feature 'say';

############################
# Closure variable capture tests
# Closures must capture the variable container (not a snapshot of the value),
# so mutations in the outer scope are visible inside the closure and vice versa.

# Anonymous sub sees outer variable updates
{
    my $x = 0;
    my $f = sub { return $x };
    $x = 42;
    is($f->(), 42, "anon sub sees outer scalar update");
}

# Anonymous sub can modify outer variable
{
    my $x = 0;
    my $f = sub { $x = 99 };
    $f->();
    is($x, 99, "anon sub modifies outer scalar");
}

# Bidirectional: outer update visible, inner update visible
{
    my $x = 1;
    my $f = sub { my $old = $x; $x = $x * 10; return $old };
    $x = 5;
    my $got = $f->();
    is($got, 5,  "anon sub reads updated outer value");
    is($x,   50, "anon sub writes back to outer variable");
}

# Named sub sees outer variable updates
{
    my $x = 0;
    sub check_x { return $x }
    $x = 42;
    is(check_x(), 42, "named sub sees outer scalar update");
}

# Named sub can modify outer variable
{
    my $x = 0;
    sub set_x { $x = 99 }
    set_x();
    is($x, 99, "named sub modifies outer scalar");
}

# Closure over array reference
{
    my @arr = (1, 2, 3);
    my $f = sub { return join(",", @arr) };
    push @arr, 4;
    is($f->(), "1,2,3,4", "anon sub sees outer array update");
}

# Closure over hash reference
{
    my %h = (a => 1);
    my $f = sub { return $h{a} };
    $h{a} = 42;
    is($f->(), 42, "anon sub sees outer hash update");
}

# Multiple closures share the same variable
{
    my $x = 0;
    my $inc = sub { $x++ };
    my $get = sub { return $x };
    $inc->();
    $inc->();
    is($get->(), 2, "two closures share the same variable");
    $x = 100;
    is($get->(), 100, "outer update visible through second closure");
}

# Closure in a loop - each iteration gets its own variable
{
    my @closures;
    for my $i (1..3) {
        push @closures, sub { return $i };
    }
    is($closures[0]->(), 1, "loop closure captures iteration 1");
    is($closures[1]->(), 2, "loop closure captures iteration 2");
    is($closures[2]->(), 3, "loop closure captures iteration 3");
}

# Nested closures
{
    my $x = 10;
    my $outer = sub {
        my $y = 20;
        my $inner = sub { return $x + $y };
        $y = 30;
        return $inner;
    };
    $x = 100;
    my $inner = $outer->();
    is($inner->(), 130, "nested closure sees both outer updates");
}

# Closure capture inside `while {} continue { ... }` block of a sub
# Regression test: VariableCollectorVisitor.visit(For3Node) used to skip
# continueBlock, so the selective-capture optimisation in SubroutineParser
# would drop variables only referenced from the continue block. The lazy
# compiler then failed at first call with
#   Global symbol "$nillio" requires explicit package name
# This was discovered via HTML/Element.pm look_down() in HTML-Tree 5.07.
{
    my $captured = [42];
    my $foo = sub {
        my @pile = (1);
        my @out;
        my $this;
        while (defined($this = shift @pile)) {
            push @out, $this;
        }
        continue {
            push @out, @{$captured};
        }
        return @out;
    };
    is_deeply([$foo->()], [1, 42], "continue block captures outer my variable");
}

# Same shape with named sub (forces lazy-compile path)
{
    my $sentinel = [99];
    my @drained;
    sub _drain_it {
        my @pile = (1, 2);
        my @out;
        my $this;
        while (defined($this = shift @pile)) {
            push @out, $this;
        }
        continue {
            push @out, @{$sentinel};
        }
        return @out;
    }
    is_deeply([_drain_it()], [1, 99, 2, 99],
              "named sub: continue block captures outer my variable");
}

done_testing();
