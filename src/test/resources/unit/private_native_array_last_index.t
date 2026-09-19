use strict;
use warnings;
use Test::More;

my @words = ();
for my $i (0 .. 31) {
    $words[$i] = $i ^ 7;
}
for my $i (0 .. $#words) {
    $words[$i] = $words[$i] ^ 17;
}

is($words[0], 22, 'last-index loop retains the initialized first word');
is($words[31], 9, 'last-index loop retains the initialized final word');
is(scalar @words, 32, 'ordinary observation materializes the completed carrier');

done_testing;
