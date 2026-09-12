use strict;
use warnings;
use Test::More;
use v5.36;

# Regression coverage for signature defaults, named arguments, and closures.

sub previous_default ($prefix, $value = $prefix . 'x') {
    return $value;
}
is previous_default('a'), 'ax', 'a default may refer to an earlier parameter';

my $anonymous_default = sub ($value = 7) { $value };
is $anonymous_default->(), 7, 'anonymous signature defaults are evaluated';

my $default_calls = 0;
sub side_effect_default ($value = ++$default_calls) {
    return $value;
}
is side_effect_default(), 1, 'a default expression executes when the argument is absent';
is side_effect_default(9), 9, 'an explicit argument skips the default expression';

sub required_argument ($value) { $value }
ok !eval { required_argument(); 1 }, 'a missing required signature argument dies';

done_testing();
