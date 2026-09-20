use strict;
use warnings;
use Test::More;

eval q!format=
@​
for(0000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000
!;

like($@,
    qr/^syntax error at \(eval \d+\) line 4, (?:near "")?\nExecution of \(eval \d+\) aborted due to compilation errors\.\n$/,
    'malformed format at EOF reports the eval compilation diagnostic');

done_testing;
