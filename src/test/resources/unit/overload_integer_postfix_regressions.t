use strict;
use warnings;
use Test::More;

SKIP: {
    # RT #43356 was added to Perl 5.44's overload behavior; the local
    # standard-Perl oracle is 5.42 and preserves the pre-autogeneration result.
    skip 'postfix overload autogeneration requires Perl 5.44', 1 if $] < 5.044;

    use overload
        '0+'     => sub { ${$_[0]} },
        '='      => sub { ${$_[0]} },
        fallback => 1;

    my $value = bless \(my $dummy = 1), __PACKAGE__;
    is(++$value, 2, 'copy overload is followed by native increment');
}

{
    no warnings 'uninitialized';
    use integer;

    my ($value, $old);
    $value = undef;
    $old = $value--;

    ok(!defined($old), 'integer postfix decrement preserves undef old value');
    is($value, -1, 'integer postfix decrement coerces lvalue to minus one');

    $value = undef;
    $old = $value++;
    is($old, 0, 'integer postfix increment preserves zero old-value coercion');
}

done_testing;
