use strict;
use warnings;
use Test::More;

sub repeated_empty_in_scalar_context {
    my @left = (11, 12);
    my @right = (21, 22, 23);
    return (do { my $unused; @left },
            (do { my $unused; @right }) x 0);
}

is(repeated_empty_in_scalar_context(), '',
    'zero-repeat remains the final empty scalar in a comma expression');

my @list = repeated_empty_in_scalar_context();
is_deeply(\@list, [11, 12],
    'the same return expression retains list-context repetition semantics');

my $fixed_scalar = (('left', 'right') x 0);
is($fixed_scalar, '', 'fixed scalar context returns an empty string');

my @fixed_list = (('left', 'right') x 2);
is_deeply(\@fixed_list, ['left', 'right', 'left', 'right'],
    'fixed list context repeats every list element');

done_testing;
