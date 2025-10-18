#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

# Test classes for addition overload
{
    package Vector;
    use overload '+' => \&add, fallback => 1;

    sub new {
        my ($class, $x, $y) = @_;
        return bless { x => $x, y => $y }, $class;
    }

    sub add {
        my ($self, $other, $swap) = @_;
        
        # Handle different cases based on swap and operand types
        if ($swap) {
            # $other + $self case (e.g., 5 + $vector)
            if (!ref($other)) {
                # scalar + Vector
                return Vector->new($self->{x} + $other, $self->{y} + $other);
            }
            elsif (ref($other) eq 'Vector') {
                # Vector + Vector (order doesn't matter for addition)
                return Vector->new($self->{x} + $other->{x}, $self->{y} + $other->{y});
            }
        } else {
            # $self + $other case (e.g., $vector + 5)
            if (!ref($other)) {
                # Vector + scalar
                return Vector->new($self->{x} + $other, $self->{y} + $other);
            }
            elsif (ref($other) eq 'Vector') {
                # Vector + Vector
                return Vector->new($self->{x} + $other->{x}, $self->{y} + $other->{y});
            }
        }
        
        die "Cannot add " . (ref($other) || 'scalar') . " to Vector";
    }

    sub to_string {
        my $self = shift;
        return "($self->{x}, $self->{y})";
    }
}

{
    package Matrix;
    use overload '+' => \&add, fallback => 0;  # No fallback

    sub new {
        my ($class, $data) = @_;
        return bless { data => $data }, $class;
    }

    sub add {
        my ($self, $other, $swap) = @_;
        
        # Only allow Matrix + Matrix (order doesn't matter for addition)
        if (ref($other) eq 'Matrix') {
            my @result;
            for my $i (0..$#{$self->{data}}) {
                for my $j (0..$#{$self->{data}[$i]}) {
                    $result[$i][$j] = $self->{data}[$i][$j] + $other->{data}[$i][$j];
                }
            }
            return Matrix->new(\@result);
        }
        
        die "Matrix can only be added to another Matrix";
    }

    sub get {
        my ($self, $i, $j) = @_;
        return $self->{data}[$i][$j];
    }
}

{
    package NumberBox;
    use base 'Vector';
    use overload '+' => \&add_numbers;

    sub new {
        my ($class, $value) = @_;
        return bless { value => $value }, $class;
    }

    sub add_numbers {
        my ($self, $other, $swap) = @_;
        
        if (!ref($other)) {
            # NumberBox + scalar or scalar + NumberBox
            return NumberBox->new($self->{value} + $other);
        }
        elsif (ref($other) eq 'NumberBox') {
            # NumberBox + NumberBox
            return NumberBox->new($self->{value} + $other->{value});
        }
        
        die "Cannot add " . ref($other) . " to NumberBox";
    }

    sub get_value {
        my $self = shift;
        return $self->{value};
    }
}

{
    package SwapTester;
    use overload '+' => \&add_with_swap_info;

    sub new {
        my ($class, $name, $value) = @_;
        return bless { name => $name, value => $value }, $class;
    }

    sub add_with_swap_info {
        my ($self, $other, $swap) = @_;
        my $swap_text = $swap ? " (swapped)" : " (not swapped)";
        my $other_name = ref($other) ? $other->{name} : $other;
        
        return SwapTester->new(
            $self->{name} . "+" . $other_name . $swap_text,
            $self->{value} + (ref($other) ? $other->{value} : $other)
        );
    }

    sub get_info {
        my $self = shift;
        return "$self->{name}: $self->{value}";
    }
}

{
    package StrictAdder;
    use overload '+' => \&strict_add, fallback => 0;

    sub new {
        my ($class, $value) = @_;
        return bless { value => $value }, $class;
    }

    sub strict_add {
        my ($self, $other, $swap) = @_;
        
        # Only allow StrictAdder + StrictAdder
        if (ref($other) eq 'StrictAdder') {
            return StrictAdder->new($self->{value} + $other->{value});
        }
        
        die "StrictAdder only works with other StrictAdder objects";
    }

    sub get_value {
        my $self = shift;
        return $self->{value};
    }
}

# Create test objects
my $v1 = Vector->new(1, 2);
my $v2 = Vector->new(3, 4);
my $v3 = Vector->new(10, 20);

my $m1 = Matrix->new([[1, 2], [3, 4]]);
my $m2 = Matrix->new([[5, 6], [7, 8]]);

my $nb1 = NumberBox->new(100);
my $nb2 = NumberBox->new(200);

my $st1 = SwapTester->new("A", 5);
my $st2 = SwapTester->new("B", 10);

my $sa1 = StrictAdder->new(42);
my $sa2 = StrictAdder->new(58);

# Test basic vector addition
my $result = $v1 + $v2;
is($result->{x}, 4, "vector + vector: x component");
is($result->{y}, 6, "vector + vector: y component");

# Test vector + scalar (no swap)
$result = $v1 + 5;
is($result->{x}, 6, "vector + scalar: x component");
is($result->{y}, 7, "vector + scalar: y component");

# Test scalar + vector (with swap)
$result = 5 + $v1;
is($result->{x}, 6, "scalar + vector (swap): x component");
is($result->{y}, 7, "scalar + vector (swap): y component");

# Test matrix addition
$result = $m1 + $m2;
is($result->get(0,0), 6, "matrix addition: element [0,0]");
is($result->get(0,1), 8, "matrix addition: element [0,1]");
is($result->get(1,0), 10, "matrix addition: element [1,0]");
is($result->get(1,1), 12, "matrix addition: element [1,1]");

# Test matrix addition with no fallback (should work)
eval {
    $result = $m1 + $m2;
};
ok(!$@, "matrix + matrix succeeds with fallback=0");

# Test matrix + scalar (should fail due to no fallback)
eval {
    $result = $m1 + 5;
};
ok($@, "matrix + scalar fails correctly with no fallback");
like($@, qr/Matrix can only be added/, "correct error message for matrix + scalar");

# Test NumberBox addition
$result = $nb1 + $nb2;
is($result->get_value(), 300, "NumberBox + NumberBox");

# Test NumberBox + scalar
$result = $nb1 + 50;
is($result->get_value(), 150, "NumberBox + scalar");

# Test scalar + NumberBox
$result = 50 + $nb1;
is($result->get_value(), 150, "scalar + NumberBox (swap)");

# Test inheritance
isa_ok($nb1, 'Vector', "NumberBox inherits from Vector");

# Test swap flag detection
$result = $st1 + $st2;
like($result->get_info(), qr/A\+B.*not swapped/, "no swap for object + object");

# Test swap flag with scalar on left
$result = 15 + $st1;
like($result->get_info(), qr/A\+15.*swapped/, "swap detected for scalar + object");

# Test chained addition
$result = $v1 + $v2 + $v3;
is($result->{x}, 14, "chained addition: x component");
is($result->{y}, 26, "chained addition: y component");

# Test addition in array context
my @vectors = ($v1, $v2);
$result = $vectors[0] + $vectors[1];
is($result->{x}, 4, "array context addition: x component");
is($result->{y}, 6, "array context addition: y component");

# Test overload method existence
can_ok($v1, 'add');

# Test addition in eval context
eval {
    $result = $v1 + $v2;
};
ok(!$@, "addition overload works in eval context");

# Test that overload works with different operand orders
my $left_result = $v1 + 10;
my $right_result = 10 + $v1;
is($left_result->{x}, $right_result->{x}, "commutative addition: x component");
is($left_result->{y}, $right_result->{y}, "commutative addition: y component");

# Test StrictAdder (fallback=0) with valid operation
$result = $sa1 + $sa2;
is($result->get_value(), 100, "StrictAdder + StrictAdder works");

# Test StrictAdder with scalar (should fail)
eval {
    $result = $sa1 + 5;
};
ok($@, "StrictAdder + scalar fails with fallback=0");

# Test scalar + StrictAdder (should also fail)
eval {
    $result = 5 + $sa1;
};
ok($@, "scalar + StrictAdder fails with fallback=0");

# Test error handling for incompatible types
eval {
    $result = $v1 + {};  # Adding a hash reference
};
ok($@, "vector + hashref fails correctly");

# Test zero addition
$result = $v1 + 0;
is($result->{x}, 1, "vector + 0: x component unchanged");
is($result->{y}, 2, "vector + 0: y component unchanged");

# Test negative number addition
$result = $v1 + (-1);
is($result->{x}, 0, "vector + negative: x component");
is($result->{y}, 1, "vector + negative: y component");

done_testing();
