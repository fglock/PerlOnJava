use strict;
use warnings;
use Test::More;

my @events;
FILE: for my $value (1 .. 3) {
    push @events, "body:$value";
    next FILE if $value < 3;
    push @events, "tail:$value";
}
continue {
    push @events, "continue:$value";
}

is_deeply(
    \@events,
    [
        'body:1', 'continue:1',
        'body:2', 'continue:2',
        'body:3', 'tail:3', 'continue:3',
    ],
    'labeled next executes the foreach continue block',
);

my @unlabeled;
for my $value (1 .. 2) {
    push @unlabeled, "body:$value";
    next;
}
continue {
    push @unlabeled, "continue:$value";
}

is_deeply(
    \@unlabeled,
    ['body:1', 'continue:1', 'body:2', 'continue:2'],
    'unlabeled next uses the same foreach continue boundary',
);

my @last;
LAST: for my $value (1 .. 2) {
    push @last, "body:$value";
    last LAST;
}
continue {
    push @last, "continue:$value";
}

is_deeply(
    \@last,
    ['body:1'],
    'last exits without executing the foreach continue block',
);

done_testing;
