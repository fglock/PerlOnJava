use strict;
use warnings;
use Test::More;

sub bind_and_format {
    my ($left, $right, $missing) = @_;
    return join ':', map { defined $_ ? $_ : 'undef' } $left, $right, $missing;
}

is bind_and_format('alpha', 'beta'), 'alpha:beta:undef',
    'void-context parameter binding preserves supplied and missing arguments';

my ($first, $second) = ('left', 'right');
($first, $second) = ($second, $first);
is_deeply [$first, $second], ['right', 'left'],
    'void list assignment preserves RHS values before aliased LHS writes';

my ($one, $two);
my $count = scalar(($one, $two) = qw(one two));
is $count, 2, 'non-void list assignment still returns its scalar element count';
is_deeply [$one, $two], [qw(one two)],
    'non-void list assignment still assigns every element';

done_testing;
