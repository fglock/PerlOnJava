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
    ['\\p{x', qr/^Missing right brace on \\p\{\}.*\\p\{\s+<-- HERE x/s],
    ['[\\P{x]', qr/^Missing right brace on \\P\{\}.*\[\\P\{\s+<-- HERE x\]/s],
    ["\\c\x{100}", qr/^Character following "\\c" must be printable ASCII.*\\c\x{100}\s+<-- HERE/s],
    ['(?[ \\cK + ])', qr/^Incomplete expression within '\(\?\[ \]\)'.*\\cK \+\s+<-- HERE \]\)/s],
    ['(?[ ( ) ])', qr/^Incomplete expression within '\(\?\[ \]\)'.*\( \)\s+<-- HERE/s],
    ['(?[ \\t ]', qr/^Unexpected '\]' with no following '\)' in \(\?\[\.\.\..*\\t \]\s+<-- HERE/s],
    ['(?[ \\05 ])', qr/^Need exactly 3 octal digits.*\\05\s+<-- HERE \]\)/s],
    ['(?[[[:w:]]])', qr/^Unexpected '\]' with no following '\)' in \(\?\[\.\.\..*\[\[:w:\]\]\s+<-- HERE \]\)/s],
    ['(?[ [ \\t ]', qr/^Syntax error in \(\?\[\.\.\.\]\).*\[ \\t \]\s+<-- HERE/s],
    ['(?[ \\t + \\e # comment ])', qr/^Syntax error in \(\?\[\.\.\.\]\).*# comment \]\)\s+<-- HERE/s],
);

for my $case (@cases) {
    my ($source, $expected) = @$case;
    my $error = compile_error($source);
    ok(length($error), "$source is rejected");
    like($error, $expected, "$source preserves diagnostic identity");
}
