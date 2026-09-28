use v5.36;
use warnings;
use Test::More;

my @values = ('A', 'B', 'C');
my $result = eval {
    for my ($left, $right) (@values) {
        $left = lc $left;
        $right = lc($right // '');
    }
    54;
};
is $result, undef, 'assignment to a padded iterator fails';
like $@, qr/Modification of a read-only value attempted/,
    'padding is a read-only undef alias';
is_deeply \@values, ['a', 'b', 'c'],
    'real iterator aliases retain modifications before the failure';
done_testing;
