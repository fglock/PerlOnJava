use strict;
use warnings;
use Test::More;

for my $source (
    q{use re 'eval'; qr/(?(??{}))/},
    q{use re 'strict'; use re 'eval'; qr/(?(??{}))/},
) {
    my $compiled = eval $source;
    ok(!$compiled, 'dynamic regex code is invalid as a conditional operand');
    like($@, qr/^Unknown switch condition \(\?\(\.\.\.\)\) in regex;/,
        'reports the conditional diagnostic');
    like($@, qr/m\/\(\?\(\? <-- HERE \?\{\}\)\)\//,
        'retains and marks the original dynamic callback spelling');
}

done_testing;
