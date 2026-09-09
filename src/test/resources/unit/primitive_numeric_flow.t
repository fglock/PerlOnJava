use strict;
use warnings;
use Test::More;

{
    my $total = 0;
    for (my $i = 0; $i < 100; $i++) {
        $total = $total + 2;
    }
    is($total, 200, 'closed lexical integer loop preserves arithmetic');
}

{
    package PrimitiveNumericFlow::Add;
    use overload '+' => sub { bless { value => $_[0]{value} + $_[1] }, __PACKAGE__ }, fallback => 1;
    sub new { bless { value => $_[1] }, $_[0] }
    sub value { $_[0]{value} }
}

{
    my $value = 1;
    for (my $i = 0; $i < 1; $i++) {
        $value = PrimitiveNumericFlow::Add->new(40);
        $value = $value + 2;
    }
    isa_ok($value, 'PrimitiveNumericFlow::Add', 'overloaded value bails out to Perl operator');
    is($value->value, 42, 'overload result is retained after bailout');
}

{
    my $value = 1;
    my $alias = \$value;
    for (my $i = 0; $i < 1; $i++) {
        $value = $value + 2;
    }
    is($$alias, 3, 'reference alias observes the assigned lexical value');
}

{
    my $value = 9_223_372_036_854_775_807;
    for (my $i = 0; $i < 1; $i++) {
        $value = $value + 1;
    }
    is("$value", '9223372036854775808', 'integer overflow bails out to the ordinary wide-integer operator');
}

{
    my $value = 11;
    for (1 .. 2_048) {
        $value = ($value * 33 + $_) % 1_000_003;
    }
    is($value, 167_688, 'nested integer recurrence preserves the ordinary operator result');
}

done_testing;
