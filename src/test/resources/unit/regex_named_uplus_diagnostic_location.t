use strict;
use warnings;
use Test::More;

for my $pattern ('\\N{U+.}', '\\N{U+_100}', '\\N{U+100_}') {
    my $ok = eval { qr/$pattern/; 1 };
    ok(!$ok, 'malformed U+ named character is rejected');
    like($@, qr/Invalid hexadecimal number in \\N\{U\+\.\.\.\} in regex; marked by <-- HERE in m\//,
        'malformed U+ diagnostic includes regex source');
}

done_testing;
