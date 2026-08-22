use strict;
use warnings;
use Test::More;

no warnings qw(experimental::regex_sets experimental::uniprop_wildcards);

my $single_name = eval q{qr/(?[ \p{name=LATIN CAPITAL LETTER A} ])/};
is($@, '', 'single-code-point Name property is valid in an extended class');
ok('A' =~ $single_name, 'single-code-point Name property keeps its member')
    if defined $single_name;

for my $name ('KATAKANA LETTER AINU P', 'katakana_letter_ainu_p') {
    my $sequence = eval 'qr/(?[ \\p{name=' . $name . '} ])/';
    like($@, qr/Unicode string properties are not implemented in \(\?\[\.\.\.\]\)/,
        "$name is rejected as an actual string property");
    ok(index($@, "\\p{name=$name} <-- HERE") >= 0,
        "$name rejection is positioned after the property brace");
    ok(!defined $sequence, "$name does not produce an extended-class regex");
}

our $deferred_error;
BEGIN {
    eval q{qr/(?[ \p{InNativeContext} + [_] ])/};
    $deferred_error = $@;
}
sub InNativeContext { "0600\n" }

like($deferred_error, qr/Unknown user-defined property name "InNativeContext"/,
    'deferred user property remains forbidden in an extended class');
ok(index($deferred_error, '\\p{InNativeContext} <-- HERE') >= 0,
    'deferred user-property rejection is positioned after the property brace');

done_testing;
