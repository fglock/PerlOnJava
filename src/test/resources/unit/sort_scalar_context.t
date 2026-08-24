use strict;
use warnings;
no warnings 'void';
use Test::More;

my $comparisons = 0;
my $sorted = sort {
    $comparisons++;
    $a cmp $b;
} qw(c b a);
ok(!defined($sorted), 'sort returns undef in scalar context');
is($comparisons, 0, 'sort comparator is not called in scalar context');

my $evaluations = 0;
my $side_effect_result = sort ($evaluations++, 3, 2);
ok(!defined($side_effect_result),
    'scalar sort remains undef with an evaluated input expression');
is($evaluations, 1, 'scalar sort still evaluates its input expression');

my $sin_result;
{
    no warnings;
    local $_ = '3 2 1';
    $sin_result = sin sort split;
}
is($sin_result, 0,
    'scalar unary operator accepts the undef result of scalar sort');

done_testing;
