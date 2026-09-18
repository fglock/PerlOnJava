use strict;
use warnings;
use Test::More;

eval q{format=
@
.//
.
};

like($@,
    qr/^syntax error at \(eval \d+\) line 3,/,
    'invalid dot format argument is rejected while compiling the format');

my $still_parses = eval q{my $value = 1; $value};
is($still_parses, 1, 'format diagnostic does not corrupt the following parser state');

done_testing;
