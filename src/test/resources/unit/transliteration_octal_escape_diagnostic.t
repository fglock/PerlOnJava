use strict;
use warnings;
use Test::More;

my $result = eval q{tr/\o-0//};
ok(!defined $result, 'malformed braced octal escape in transliteration fails');
like($@,
    qr{\AMissing braces on \\o\{\} at \(eval \d+\) line 1, within string\n?\z},
    'transliteration reports the braced-octal diagnostic before range parsing');

done_testing;
