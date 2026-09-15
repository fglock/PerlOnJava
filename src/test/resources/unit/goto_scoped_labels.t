use strict;
use warnings;
use Test::More;

my @seen;

{
    goto A;
    push @seen, 'wrong-first';
    A: push @seen, 'first';
}

{
    goto A;
    push @seen, 'wrong-second';
    A: push @seen, 'second';
}

{
    my $runs = 0;
    A: $runs++;
    goto A if $runs == 1;
    push @seen, "backward-$runs";
}

is_deeply(\@seen, [qw(first second backward-2)],
    'static goto resolves the nearest scoped label in both directions');

done_testing();
