use strict;
use warnings;
use Test::More;

# Test class with numeric comparison overloading
{
    package NumberValue;
    use overload
        '<=>' => \&compare,
        'cmp' => \&string_compare,
        '""' => \&stringify,
        fallback => 1;

    sub new {
        my ($class, $value) = @_;
        return bless { value => $value }, $class;
    }

    sub compare {
        my ($self, $other, $swap) = @_;
        my $self_val = $self->{value};
        my $other_val = ref($other) eq 'NumberValue' ? $other->{value} : $other;
        return $swap ? $other_val <=> $self_val : $self_val <=> $other_val;
    }

    sub string_compare {
        my ($self, $other, $swap) = @_;
        my $self_str = "Value:" . $self->{value};
        my $other_str = ref($other) eq 'NumberValue' ? "Value:" . $other->{value} : $other;
        return $swap ? $other_str cmp $self_str : $self_str cmp $other_str;
    }

    sub stringify {
        my $self = shift;
        return "Value:" . $self->{value};
    }
}

# Test class with individual comparison operators
{
    package CustomCompare;
    use overload
        '<' => \&less_than,
        '<=' => \&less_equal,
        '>' => \&greater_than,
        '>=' => \&greater_equal,
        '==' => \&equal,
        '!=' => \&not_equal,
        'lt' => \&string_lt,
        'le' => \&string_le,
        'gt' => \&string_gt,
        'ge' => \&string_ge,
        'eq' => \&string_eq,
        'ne' => \&string_ne,
        '""' => \&to_string;

    sub new {
        my ($class, $value) = @_;
        return bless { value => $value, comparisons => 0 }, $class;
    }

    sub less_than {
        my ($self, $other, $swap) = @_;
        $self->{comparisons}++;
        my $result = $swap ? $other < $self->{value} : $self->{value} < $other;
        return $result;
    }

    sub less_equal {
        my ($self, $other, $swap) = @_;
        $self->{comparisons}++;
        my $result = $swap ? $other <= $self->{value} : $self->{value} <= $other;
        return $result;
    }

    sub greater_than {
        my ($self, $other, $swap) = @_;
        $self->{comparisons}++;
        my $result = $swap ? $other > $self->{value} : $self->{value} > $other;
        return $result;
    }

    sub greater_equal {
        my ($self, $other, $swap) = @_;
        $self->{comparisons}++;
        my $result = $swap ? $other >= $self->{value} : $self->{value} >= $other;
        return $result;
    }

    sub equal {
        my ($self, $other, $swap) = @_;
        $self->{comparisons}++;
        return $self->{value} == $other;
    }

    sub not_equal {
        my ($self, $other, $swap) = @_;
        $self->{comparisons}++;
        return $self->{value} != $other;
    }

    sub string_lt {
        my ($self, $other, $swap) = @_;
        my $self_str = "Str" . $self->{value};
        my $other_str = ref($other) eq 'CustomCompare' ? "Str" . $other->{value} : $other;
        return $swap ? $other_str lt $self_str : $self_str lt $other_str;
    }

    sub string_le {
        my ($self, $other, $swap) = @_;
        my $self_str = "Str" . $self->{value};
        my $other_str = ref($other) eq 'CustomCompare' ? "Str" . $other->{value} : $other;
        return $swap ? $other_str le $self_str : $self_str le $other_str;
    }

    sub string_gt {
        my ($self, $other, $swap) = @_;
        my $self_str = "Str" . $self->{value};
        my $other_str = ref($other) eq 'CustomCompare' ? "Str" . $other->{value} : $other;
        return $swap ? $other_str gt $self_str : $self_str gt $other_str;
    }

    sub string_ge {
        my ($self, $other, $swap) = @_;
        my $self_str = "Str" . $self->{value};
        my $other_str = ref($other) eq 'CustomCompare' ? "Str" . $other->{value} : $other;
        return $swap ? $other_str ge $self_str : $self_str ge $other_str;
    }

    sub string_eq {
        my ($self, $other, $swap) = @_;
        my $self_str = "Str" . $self->{value};
        my $other_str = ref($other) eq 'CustomCompare' ? "Str" . $other->{value} : $other;
        return $self_str eq $other_str;
    }

    sub string_ne {
        my ($self, $other, $swap) = @_;
        my $self_str = "Str" . $self->{value};
        my $other_str = ref($other) eq 'CustomCompare' ? "Str" . $other->{value} : $other;
        return $self_str ne $other_str;
    }

    sub to_string {
        my $self = shift;
        return "Str" . $self->{value};
    }
}

# Test class with only spaceship operator (tests fallback)
{
    package SpaceshipOnly;
    use overload
        '<=>' => \&spaceship,
        fallback => 1;

    sub new {
        my ($class, $value) = @_;
        return bless { value => $value }, $class;
    }

    sub spaceship {
        my ($self, $other, $swap) = @_;
        my $other_val = ref($other) eq 'SpaceshipOnly' ? $other->{value} : $other;
        return $swap ? $other_val <=> $self->{value} : $self->{value} <=> $other_val;
    }
}

# Test class with only cmp operator (tests string fallback)
{
    package CmpOnly;
    use overload
        'cmp' => \&compare_string,
        fallback => 1;

    sub new {
        my ($class, $value) = @_;
        return bless { value => $value }, $class;
    }

    sub compare_string {
        my ($self, $other, $swap) = @_;
        my $self_str = "Item" . $self->{value};
        my $other_str = ref($other) eq 'CmpOnly' ? "Item" . $other->{value} : $other;
        return $swap ? $other_str cmp $self_str : $self_str cmp $other_str;
    }
}

subtest 'Numeric comparison with spaceship operator' => sub {
    my $num1 = NumberValue->new(10);
    my $num2 = NumberValue->new(20);
    my $num3 = NumberValue->new(10);

    # Direct spaceship tests
    is($num1 <=> $num2, -1, 'spaceship: 10 <=> 20 = -1');
    is($num2 <=> $num1, 1, 'spaceship: 20 <=> 10 = 1');
    is($num1 <=> $num3, 0, 'spaceship: 10 <=> 10 = 0');

    # Numeric comparisons using fallback to spaceship
    ok($num1 < $num2, '10 < 20');
    ok(!($num2 < $num1), '!(20 < 10)');
    ok(!($num1 < $num3), '!(10 < 10)');

    ok($num1 <= $num2, '10 <= 20');
    ok($num1 <= $num3, '10 <= 10');
    ok(!($num2 <= $num1), '!(20 <= 10)');

    ok($num2 > $num1, '20 > 10');
    ok(!($num1 > $num2), '!(10 > 20)');
    ok(!($num1 > $num3), '!(10 > 10)');

    ok($num2 >= $num1, '20 >= 10');
    ok($num1 >= $num3, '10 >= 10');
    ok(!($num1 >= $num2), '!(10 >= 20)');

    ok($num1 == $num3, '10 == 10');
    ok(!($num1 == $num2), '!(10 == 20)');

    ok($num1 != $num2, '10 != 20');
    ok(!($num1 != $num3), '!(10 != 10)');

    # Test with plain numbers
    ok($num1 < 15, 'object < number');
    ok(15 > $num1, 'number > object');
    ok($num1 == 10, 'object == number');
};

subtest 'String comparison with cmp operator' => sub {
    my $str1 = NumberValue->new(10);
    my $str2 = NumberValue->new(20);
    my $str3 = NumberValue->new(10);

    # Direct cmp tests
    is($str1 cmp $str2, -1, 'cmp: Value:10 cmp Value:20 = -1');
    is($str2 cmp $str1, 1, 'cmp: Value:20 cmp Value:10 = 1');
    is($str1 cmp $str3, 0, 'cmp: Value:10 cmp Value:10 = 0');

    # String comparisons using fallback to cmp
    ok($str1 lt $str2, 'Value:10 lt Value:20');
    ok(!($str2 lt $str1), '!(Value:20 lt Value:10)');
    ok(!($str1 lt $str3), '!(Value:10 lt Value:10)');

    ok($str1 le $str2, 'Value:10 le Value:20');
    ok($str1 le $str3, 'Value:10 le Value:10');
    ok(!($str2 le $str1), '!(Value:20 le Value:10)');

    ok($str2 gt $str1, 'Value:20 gt Value:10');
    ok(!($str1 gt $str2), '!(Value:10 gt Value:20)');
    ok(!($str1 gt $str3), '!(Value:10 gt Value:10)');

    ok($str2 ge $str1, 'Value:20 ge Value:10');
    ok($str1 ge $str3, 'Value:10 ge Value:10');
    ok(!($str1 ge $str2), '!(Value:10 ge Value:20)');

    ok($str1 eq $str3, 'Value:10 eq Value:10');
    ok(!($str1 eq $str2), '!(Value:10 eq Value:20)');

    ok($str1 ne $str2, 'Value:10 ne Value:20');
    ok(!($str1 ne $str3), '!(Value:10 ne Value:10)');

    # Test with plain strings
    ok($str1 lt "Value:15", 'object lt string');
    ok("Value:15" gt $str1, 'string gt object');
    ok($str1 eq "Value:10", 'object eq string');
};

subtest 'Individual comparison operators' => sub {
    my $obj1 = CustomCompare->new(5);
    my $obj2 = CustomCompare->new(10);

    # Numeric comparisons
    ok($obj1 < $obj2, 'custom < operator');
    ok($obj1 <= $obj2, 'custom <= operator');
    ok($obj2 > $obj1, 'custom > operator');
    ok($obj2 >= $obj1, 'custom >= operator');
    ok($obj1 == 5, 'custom == operator');
    ok($obj1 != $obj2, 'custom != operator');

    # String comparisons
    ok($obj2 lt $obj1, 'custom lt operator (Str10 lt Str5)');
    ok($obj2 le $obj1, 'custom le operator');
    ok($obj1 gt $obj2, 'custom gt operator (Str5 gt Str10)');
    ok($obj1 ge $obj2, 'custom ge operator');
    ok($obj1 eq "Str5", 'custom eq operator');
    ok($obj1 ne $obj2, 'custom ne operator');

    # Check that comparisons were counted
    cmp_ok($obj1->{comparisons}, '>', 0, 'comparison methods were called');
};

subtest 'Spaceship-only fallback' => sub {
    my $ship1 = SpaceshipOnly->new(100);
    my $ship2 = SpaceshipOnly->new(200);

    # All numeric comparisons should work via fallback
    ok($ship1 < $ship2, 'fallback: < via <=>');
    ok($ship1 <= $ship2, 'fallback: <= via <=>');
    ok($ship2 > $ship1, 'fallback: > via <=>');
    ok($ship2 >= $ship1, 'fallback: >= via <=>');
    ok($ship1 == 100, 'fallback: == via <=>');
    ok($ship1 != $ship2, 'fallback: != via <=>');

    # Test spaceship directly
    is($ship1 <=> $ship2, -1, 'direct spaceship call');
    is($ship1 <=> 100, 0, 'spaceship with number');
};

subtest 'Cmp-only fallback' => sub {
    my $cmp1 = CmpOnly->new(1);
    my $cmp2 = CmpOnly->new(2);

    # All string comparisons should work via fallback
    ok($cmp1 lt $cmp2, 'fallback: lt via cmp');
    ok($cmp1 le $cmp2, 'fallback: le via cmp');
    ok($cmp2 gt $cmp1, 'fallback: gt via cmp');
    ok($cmp2 ge $cmp1, 'fallback: ge via cmp');
    ok($cmp1 eq "Item1", 'fallback: eq via cmp');
    ok($cmp1 ne $cmp2, 'fallback: ne via cmp');

    # Test cmp directly
    is($cmp1 cmp $cmp2, -1, 'direct cmp call');
    is($cmp1 cmp "Item1", 0, 'cmp with string');
};

subtest 'Mixed object comparisons' => sub {
    my $num = NumberValue->new(10);
    my $custom = CustomCompare->new(10);

    # These should use the overloaded operators
    ok($num == 10, 'NumberValue == plain number');
    ok($custom == 10, 'CustomCompare == plain number');

    # String comparison between different classes
    ok($num ne $custom, 'Different classes with different string representations');
};

subtest 'Edge cases' => sub {
    my $zero = NumberValue->new(0);
    my $negative = NumberValue->new(-5);
    my $positive = NumberValue->new(5);

    # Zero comparisons
    ok($zero == 0, 'zero == 0');
    ok($negative < $zero, 'negative < zero');
    ok($positive > $zero, 'positive > zero');

    # Negative comparisons
    ok($negative < $positive, 'negative < positive');
    ok($negative <= $negative, 'negative <= negative');

    # String edge cases
    my $empty = CmpOnly->new("");
    my $space = CmpOnly->new(" ");
    ok($empty lt $space, 'empty string lt space');

    # Comparison with undef (should stringify to empty)
    my $undef_obj = NumberValue->new(undef);
    ok($undef_obj lt $zero, 'undef object lt zero object');
};

subtest 'Chained comparisons' => sub {
    my $a = NumberValue->new(1);
    my $b = NumberValue->new(2);
    my $c = NumberValue->new(3);

    # Test that comparisons return proper boolean values for chaining
    ok($a < $b && $b < $c, 'chained < comparisons');
    ok($a <= $b && $b <= $c, 'chained <= comparisons');
    ok(!($a > $b || $b > $c), 'chained > comparisons (negated)');

    # String chaining
    ok($a lt $b && $b lt $c, 'chained lt comparisons');
};

subtest 'Swap parameter handling' => sub {
    my $obj = NumberValue->new(10);

    # Test swapped comparisons (when object is on right side)
    ok(5 < $obj, '5 < object(10)');
    ok(!(15 < $obj), '!(15 < object(10))');
    ok(10 <= $obj, '10 <= object(10)');
    ok(15 > $obj, '15 > object(10)');
    ok(10 >= $obj, '10 >= object(10)');
    ok(10 == $obj, '10 == object(10)');
    ok(5 != $obj, '5 != object(10)');

    # String swap tests
    ok("Value:05" lt $obj, 'string lt object');
    ok("Value:10" le $obj, 'string le object');
    ok("Value:15" gt $obj, 'string gt object');
    ok("Value:10" ge $obj, 'string ge object');
    ok("Value:10" eq $obj, 'string eq object');
    ok("Value:15" ne $obj, 'string ne object');
};

subtest 'Comparison in different contexts' => sub {
    my $obj1 = NumberValue->new(10);
    my $obj2 = NumberValue->new(20);

    # In if conditions
    if ($obj1 < $obj2) {
        pass('comparison in if condition');
    } else {
        fail('comparison in if condition');
    }

    # In ternary operator
    my $result = $obj1 > $obj2 ? "greater" : "not greater";
    is($result, "not greater", 'comparison in ternary operator');

    # In array index (boolean to number conversion)
    my @arr = ('false', 'true');
    is($arr[$obj1 < $obj2], 'true', 'comparison result as array index');

    # In string context
    my $cmp_result = $obj1 <=> $obj2;
    is("$cmp_result", "-1", 'spaceship result stringifies correctly');
};

subtest 'Inheritance and overloading' => sub {
    {
        package DerivedNumber;
        use base 'NumberValue';

        # Override spaceship to double the comparison
        sub compare {
            my ($self, $other, $swap) = @_;
            my $result = $self->SUPER::compare($other, $swap);
            return $result * 2;  # -2, 0, or 2
        }
    }

    my $derived = DerivedNumber->new(10);
    my $base = NumberValue->new(20);

    # The derived class should use its overridden compare
    is($derived <=> $base, -1, 'derived class spaceship returns -1');

    # But comparison operators should still work normally
    ok($derived < $base, 'derived < base still works');
    ok(!($derived > $base), '!(derived > base) still works');
};

subtest 'Multiple overload interactions' => sub {
    {
        package MultiOverload;
        use overload
            '<=>' => sub {
                my ($self, $other, $swap) = @_;
                return $swap ? $other <=> $self->{num} : $self->{num} <=> $other;
            },
            'cmp' => sub {
                my ($self, $other, $swap) = @_;
                my $str = "Multi$self->{num}";
                return $swap ? $other cmp $str : $str cmp $other;
            },
            '""' => sub { "Multi$_[0]->{num}" },
            fallback => 1;

        sub new {
            my ($class, $num) = @_;
            return bless { num => $num }, $class;
        }
    }

    my $multi1 = MultiOverload->new(5);
    my $multi2 = MultiOverload->new(10);

    # Numeric comparisons should use <=>
    ok($multi1 < $multi2, 'multi: numeric <');
    ok($multi1 <= $multi2, 'multi: numeric <=');

    # String comparisons should use cmp
    ok($multi2 lt $multi1, 'multi: string lt (Multi10 lt Multi5)');
    ok($multi2 le $multi1, 'multi: string le');

    # Direct calls
    is($multi1 <=> $multi2, -1, 'multi: direct <=>');
    is($multi1 cmp $multi2, 1, 'multi: direct cmp');
    is("$multi1", "Multi5", 'multi: stringification');
};

done_testing();
