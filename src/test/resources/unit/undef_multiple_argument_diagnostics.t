use strict;
use warnings;
use Test::More;

my $result = eval "undef(1,2);\nundef(1,2);\n";
ok(!defined $result, 'multiple-argument undef expressions fail to compile');
like($@,
    qr{\AToo many arguments for undef operator at \(eval \d+\) line 1, near "2\)"\nToo many arguments for undef operator at \(eval \d+\) line 2, near "2\)"\n\z},
    'all malformed undef expressions report their argument locations');

done_testing;
