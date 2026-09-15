use strict;
use warnings;
use Test::More;

my @seen;

OUTER:
for my $value (0 .. 1) {
    push @seen, "head-$value";
    goto OUTER unless $value;
    push @seen, "wrong-$value";
  OUTER:
    push @seen, "inner-$value";
    last;
}

is_deeply(\@seen, [qw(head-0 inner-0)],
    'goto chooses the nearest nested label instead of an outer same-named label');

eval {
    for (0 .. 1) {
      INNER:
        last;
    }
    goto INNER;
};
like($@, qr/Can't "goto" into the middle of a foreach loop/,
    'goto from an eval cannot enter a completed foreach body');

eval {
    goto UNREACHABLE;
    if (0) {
      UNREACHABLE:
        1;
    }
};
like($@, qr/Can't find label UNREACHABLE/,
    'goto cannot enter a label optimized away with a false conditional');

eval {
    sub { goto NESTED; ref do { NESTED: [] } }->();
};
if ($] >= 5.044) {
    like($@, qr/Use of "goto" to jump into a construct is no longer permitted/,
        'goto cannot enter an expression-nested block');
} else {
    is($@, '', 'goto into an expression-nested block remains non-fatal before Perl 5.44');
}

done_testing();
