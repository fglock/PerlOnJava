#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

use Math::BigInt;
use Math::BigFloat;
use Math::BigInt::Calc;

sub full_chain {
    my $x = Math::BigInt->new("1");
    my @r;
    return Math::BigFloat->bexp($x)->bint()->round(@r)->as_int();
}

sub split_chain {
    my $x = Math::BigInt->new("1");
    my @r;
    my $float = Math::BigFloat->bexp($x);
    my $int_float = $float->bint();
    my $rounded = $int_float->round(@r);
    return $rounded->as_int();
}

{
    my $direct = eval { Math::BigInt->new("1")->bexp() };
    is($@, "", "Math::BigInt bexp chain does not die");
    is("$direct", "2", "Math::BigInt bexp returns 2");
}

{
    my $chained = eval { full_chain() };
    is($@, "", "Math::BigFloat intermediate method chain does not die");
    is("$chained", "2", "Math::BigFloat chained result converts back to 2");
}

{
    my $split = eval { split_chain() };
    is($@, "", "split intermediate control does not die");
    is("$split", "2", "split intermediate control converts back to 2");
}

{
    package ForeachRestoreTemp;
    sub new { bless {}, shift }
    sub DESTROY {}

    package main;
    sub return_after_implicit_foreach {
        my ($self) = @_;
        foreach ($self) {
            my $class = ref($_);
        }
        return $self;
    }

    my $obj = eval { return_after_implicit_foreach(ForeachRestoreTemp->new()) };
    is($@, "", "implicit foreach over a temporary does not die");
    is(ref($obj), "ForeachRestoreTemp", "implicit foreach restore does not undef the aliased temporary");
}

{
    my $left = Math::BigInt::Calc->_new("9999999999999999");
    my $right = Math::BigInt::Calc->_new("10000000000000000");
    ok(!($left == $right), "inherited overload is visible after Math::BigInt::Calc cache invalidation");
}

{
    my $x = Math::BigInt->new("9999999999999999");
    my $y = Math::BigInt->new("10000000000000000");
    is($x->copy()->bmul($y), "99999999999999990000000000000000", "large bmul uses the right operand");
}

{
    my $x = Math::BigInt->new("9999999999999999999");
    my $y = Math::BigInt->new("10000000000000000000");
    my $z = Math::BigInt->new("1234567890");
    is($x->copy()->bmuladd($y, $z), "99999999999999999990000000001234567890", "large bmuladd uses the right operand");
}

{
    my $x = Math::BigFloat->new("77777");
    my $y = Math::BigFloat->new("777");
    my $z = Math::BigFloat->new("123456789");
    is($x->copy()->bmodpow($y, $z), "99995084", "bmodpow keeps Math::BigInt::Calc operands distinct");
}

done_testing;
