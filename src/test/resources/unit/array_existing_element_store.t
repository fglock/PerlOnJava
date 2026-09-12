use strict;
use warnings;
use Test::More tests => 6;

my @values = (10, 20, 30);
is($values[1] = 99, 99, 'existing element assignment returns assigned value');
is_deeply(\@values, [10, 99, 30], 'existing element assignment updates slot');

is($values[-1] = 77, 77, 'negative existing element assignment returns value');
is($values[2], 77, 'negative existing element assignment updates final slot');

sub overwrite_argument_element {
    $_[0] = 55;
    return $_[0];
}

is(overwrite_argument_element($values[0]), 55, 'argument alias assignment returns assigned value');
is($values[0], 55, 'argument alias assignment updates caller array slot');
