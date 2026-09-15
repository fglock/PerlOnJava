use strict;
use warnings;
use Test::More;

$@ = "stale error\n";
my $return_value = eval {
    eval { die "inner error\n" };
    return;
};

ok(!defined($return_value), 'return from a successful eval is undef');
is($@, '', 'a successful eval with return clears an inner eval error');

$@ = "stale error\n";
my $value = eval {
    $@ = "operator-like error\n";
    1;
};

is($value, 1, 'successful eval returns its value');
is($@, '', 'a successful eval clears an error assigned in its body');

done_testing;
