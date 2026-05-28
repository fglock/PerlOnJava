use strict;
use warnings;
use Test::More;

eval "program not perl";
like($@, qr/^syntax error at \(eval \d+\) line 1, near "program not "/,
    'eval syntax error reports Perl-compatible context before not operand');

done_testing();
