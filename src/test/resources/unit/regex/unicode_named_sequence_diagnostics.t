use strict;
use warnings;
use Test::More;

my $pattern = 'qr/(?[' . chr(92) . 'N{KEYCAP DIGIT NINE}/';
my $expected = "\\N{} here is restricted to one character in regex; "
    . "marked by <-- HERE in m/(?[\\N{U+39.FE0F.20E3 <-- HERE }/ "
    . "at unicode_named_sequence_diagnostics.t line 1.\n";

for my $strict (0, 1) {
    my $prefix = $strict
        ? "no warnings 'experimental::re_strict'; use re 'strict';\n"
        : '';
    my $source = "#line 1 unicode_named_sequence_diagnostics.t\n" . $prefix;
    $source .= "#line 1 unicode_named_sequence_diagnostics.t\n" if $strict;
    eval $source . $pattern;
    is($@, $expected,
        ($strict ? 'strict' : 'ordinary')
            . ' native extended class reports the canonical named sequence');
}

done_testing;
