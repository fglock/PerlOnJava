use strict;
use warnings;
use utf8;
use Test::More;

my $outside = '\N{ U+0100 }';
ok("\x{100}" =~ /$outside/, 'spaced U+ resolves outside a class');

my $inside = '[\N{ U+0100 }]';
ok("\x{100}" =~ /$inside/, 'spaced U+ resolves inside a class');

my $non_newline_once = '\N {1}';
ok('ab' =~ /$non_newline_once/x,
    'extended whitespace before a non-newline quantifier compiles');
is($&, 'a', 'non-newline quantifier retains its match');

my $non_newline_range = '\N {3,4}';
ok('abbbbc' =~ /$non_newline_range/x,
    'extended whitespace before a non-newline range compiles');
is($&, 'abbb', 'non-newline range retains its match');

my @invalid = (
    ['abc\N{def}', '', qr/^Unknown charname 'def'/,
        'unknown name remains fatal with its name preserved'],
    ['abc\N {U+41}', 'x', qr/^Missing braces on \\N\{\}/,
        'space under x does not separate N from brace'],
    ['abc\N {SPACE}', 'x', qr/^Missing braces on \\N\{\}/,
        'named space under x reports missing braces'],
    ['\N(?#comment){SPACE}', '', qr/^Missing braces on \\N\{\}/,
        'comment between N and brace reports missing braces'],
);

for my $case (@invalid) {
    my ($pattern, $modifiers, $expected, $label) = @$case;
    if ($modifiers eq 'x') {
        eval { qr/$pattern/x };
    } else {
        eval { qr/$pattern/ };
    }
    like($@, $expected, $label);
}

done_testing;
