use strict;
use warnings;
use Test::More;

eval q!s,,$0[sub{m[]]],;s,,$0[sub{m[]]],}}!;

like($@,
    qr/syntax error at \(eval 1\) line 1, near "m\[\]\]"\nExecution of \(eval 1\) aborted due to compilation errors\./,
    'malformed regex in a sub preserves its original eval diagnostic');

done_testing;
