use strict;
use warnings;
use Test::More;

for my $property ('IsPrint', 'Print') {
    my $plain = eval 'qr/\\A\\p{' . $property . '}\\z/u';
    ok(defined($plain), "$property compiles by itself") or diag($@);

    my $combined = eval 'qr/\\A[\\p{' . $property . '}\\s]*\\z/u';
    ok(defined($combined), "$property combines with another class property")
        or diag($@);
    if ($combined) {
        like("text\n", $combined, "$property class accepts printable text and whitespace");
        unlike("\x{E01F0}", $combined, "$property class rejects an unassigned character");
    }
}

done_testing;
