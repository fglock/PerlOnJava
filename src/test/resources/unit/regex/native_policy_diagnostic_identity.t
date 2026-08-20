use strict;
use warnings;
no warnings 'experimental::regex_sets';
use Test::More tests => 14;

sub compile_error {
    my ($source) = @_;
    eval "qr/$source/";
    return $@;
}

my @cases = (
    ['((?# comment)?:foo)', qr/^In '\(\?\.\.\.\)', the '\(' and '\?' must be adjacent.*\)\?\s+<-- HERE :foo/s],
    ['((?# comment)*FAIL)', qr/^In '\(\*VERB\.\.\.\)', the '\(' and '\*' must be adjacent.*\)\*\s+<-- HERE FAIL/s],
    ['((?# comment)*script_run:foo)', qr/^In '\(\*\.\.\.\)', the '\(' and '\*' must be adjacent.*\)\*\s+<-- HERE script_run/s],
    ["(?\x{e9})", qr/^Sequence \(\?\x{e9}\.\.\.\) not recognized.*\(\?\x{e9}\s+<-- HERE \)/s],
    ['(?[ \\p{Digit} & (?^(?[ \\p{Thai} | \\p{Lao} ]))])',
     qr/^Sequence \(\?\^\(\.\.\.\) not recognized.*\(\?\^\(\s+<-- HERE \?\[/s],
    ['(?[ \\xabcdef ])', qr/^Use \\x\{\.\.\.\} for more than two hex characters.*\\xabc\s+<-- HERE def/s],
    ['(?[ \\x{} ])', qr/^Empty \\x\{\}.*\\x\{\}\s+<-- HERE  \]\)/s],
);

for my $case (@cases) {
    my ($source, $expected) = @$case;
    my $error = compile_error($source);
    ok(length($error), "$source is rejected");
    like($error, $expected, "$source preserves native policy identity");
}
