use strict;
use warnings;
use Test::More;

our @destroyed;
{
    package LocalHashClosureDestroy;
    sub DESTROY { push @main::destroyed, 'destroyed' }
}

our %handlers;
$handlers{temporary} = 'outer handler';
{
    local $handlers{temporary} = do {
        my $captured = bless {}, 'LocalHashClosureDestroy';
        sub { $captured };
    };
}

is_deeply \@destroyed, ['destroyed'],
    'restoring a localized hash entry releases an unreachable closure capture';
is $handlers{temporary}, 'outer handler',
    'local hash entry is restored after scope exit';

done_testing;
