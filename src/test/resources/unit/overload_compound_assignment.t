#!/usr/bin/env perl
use v5.40;
use Test::More;

# Test overload support for compound assignment operators

package MyNum {
    use overload
        '+=' => sub { $_[0]{val} += $_[1]; $_[0] },
        '-=' => sub { $_[0]{val} -= $_[1]; $_[0] },
        '*=' => sub { $_[0]{val} *= $_[1]; $_[0] },
        '/=' => sub { $_[0]{val} /= $_[1]; $_[0] },
        '%=' => sub { $_[0]{val} %= $_[1]; $_[0] },
        '+' => sub { my $new = bless {val => $_[0]{val} + $_[1]}, ref $_[0]; $new },
        '-' => sub { my $new = bless {val => $_[0]{val} - $_[1]}, ref $_[0]; $new },
        '==' => sub { $_[0]{val} == $_[1] },
        '0+' => sub { $_[0]{val} },
        '""' => sub { $_[0]{val} };

    sub new { bless {val => $_[1]}, $_[0] }
}

package MyNum2 {
    # Only base operators, no compound assignment
    use overload
        '+' => sub { my $new = bless {val => $_[0]{val} + $_[1]}, ref $_[0]; $new },
        '-' => sub { my $new = bless {val => $_[0]{val} - $_[1]}, ref $_[0]; $new },
        '*' => sub { my $new = bless {val => $_[0]{val} * $_[1]}, ref $_[0]; $new },
        '/' => sub { my $new = bless {val => $_[0]{val} / $_[1]}, ref $_[0]; $new },
        '%' => sub { my $new = bless {val => $_[0]{val} % $_[1]}, ref $_[0]; $new },
        '==' => sub { $_[0]{val} == $_[1] },
        '0+' => sub { $_[0]{val} },
        '""' => sub { $_[0]{val} };

    sub new { bless {val => $_[1]}, $_[0] }
}

subtest "With += overload defined" => sub {
    my $x = MyNum->new(10);
    $x += 5;
    ok(($x + 0) == 15, "+= overload called");
};

subtest "Without += overload (fallback to +)" => sub {
    my $y = MyNum2->new(20);
    $y += 10;
    ok(($y + 0) == 30, "+= falls back to + overload");
};

subtest "With -= overload defined" => sub {
    my $z = MyNum->new(100);
    $z -= 25;
    ok(($z + 0) == 75, "-= overload called");
};

subtest "Without -= overload (fallback to -)" => sub {
    my $w = MyNum2->new(100);
    $w -= 30;
    ok(($w + 0) == 70, "-= falls back to - overload");
};

subtest "With *= overload defined" => sub {
    my $a = MyNum->new(7);
    $a *= 6;
    ok(($a + 0) == 42, "*= overload called");
};

subtest "Without *= overload (fallback to *)" => sub {
    my $b = MyNum2->new(8);
    $b *= 5;
    ok(($b + 0) == 40, "*= falls back to * overload");
};

subtest "With /= overload defined" => sub {
    my $c = MyNum->new(42);
    $c /= 6;
    ok(($c + 0) == 7, "/= overload called");
};

subtest "Without /= overload (fallback to /)" => sub {
    my $d = MyNum2->new(45);
    $d /= 5;
    ok(($d + 0) == 9, "/= falls back to / overload");
};

subtest "With %= overload defined" => sub {
    my $e = MyNum->new(23);
    $e %= 7;
    ok(($e + 0) == 2, "%= overload called");
};

subtest "Without %= overload (fallback to %)" => sub {
    my $f = MyNum2->new(29);
    $f %= 8;
    ok(($f + 0) == 5, "%= falls back to % overload");
};

done_testing();
