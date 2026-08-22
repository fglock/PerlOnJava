use strict;
use warnings;
use Test::More;

my @unlabeled;
my $unlabeled_reentries = 0;
for my $value (1 .. 3) {
    push @unlabeled, "body:$value";
}
continue {
    push @unlabeled, "continue:$value";
    next if $value == 1 && !$unlabeled_reentries++;
    push @unlabeled, "tail:$value";
}

is_deeply(
    \@unlabeled,
    [
        'body:1', 'continue:1', 'continue:1', 'tail:1',
        'body:2', 'continue:2', 'tail:2',
        'body:3', 'continue:3', 'tail:3',
    ],
    'next inside foreach continue re-enters that continue block',
);

my @labeled;
my $labeled_reentries = 0;
ITEM: for my $value (1 .. 2) {
    push @labeled, "body:$value";
}
continue {
    push @labeled, "continue:$value";
    next ITEM if $value == 1 && !$labeled_reentries++;
    push @labeled, "tail:$value";
}

is_deeply(
    \@labeled,
    [
        'body:1', 'continue:1', 'continue:1', 'tail:1',
        'body:2', 'continue:2', 'tail:2',
    ],
    'labeled next inside foreach continue uses the same re-entry point',
);

done_testing;
