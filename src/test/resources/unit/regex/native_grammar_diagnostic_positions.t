use strict;
use warnings;
no warnings 'experimental::regex_sets';
use Test::More tests => 20;

sub compile_error {
    my ($source) = @_;
    eval "qr/$source/";
    return $@;
}

my @cases = (
    ['(?(1x))', qr/^Switch condition not recognized.*\(\?\(1x\s+<-- HERE \)\)/s],
    ['(?(1)x|y|z)', qr/^Switch \(\?\(condition\)\.\.\. contains too many branches.*x\|y\|\s+<-- HERE z/s],
    ['\\x{100}(?(', qr/^Unknown switch condition \(\?\(\.\.\.\)\).*\(\?\(\s+<-- HERE/s],
    ['(?[a])', qr/^Unexpected character.*\(\?\[a\s+<-- HERE \]\)/s],
    ['(?[ \\cK \\t ])', qr/^Operand with no preceding operator.*\\cK \\t\s+<-- HERE  \]\)/s],
    ['(?[ \\p{Digit} & (?(?[ \\p{Thai} | \\p{Lao} ]))])',
     qr/^Unexpected character.*\\p\{Digit\} & \(\?\s+<-- HERE \(/s],
    ['\\x{ 1 ', qr/^Missing right brace on \\x\{\}.*\\x\{ 1\s+<-- HERE  /s],
    ['[\\x{ A ]', qr/^Missing right brace on \\x\{\}.*\[\\x\{ A\s+<-- HERE  \]/s],
    ['\\o{ 1 ', qr/^Missing right brace on \\o\{\}.*\\o\{ 1\s+<-- HERE  /s],
    ['[\\o{ 7 ]', qr/^Missing right brace on \\o\{\}.*\[\\o\{ 7\s+<-- HERE  \]/s],
);

for my $case (@cases) {
    my ($source, $expected) = @$case;
    my $error = compile_error($source);
    ok(length($error), "$source is rejected");
    like($error, $expected, "$source preserves grammar diagnostic identity");
}
