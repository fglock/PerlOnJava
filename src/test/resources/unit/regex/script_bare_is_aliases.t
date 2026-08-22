use strict;
use warnings;
use utf8;
no warnings 'experimental::uniprop_wildcards';
use Test::More;

ok("\x{1DFA}" =~ /\p{Syriac}/,
    'bare long Script alias uses Script_Extensions');
ok("\x{1DFA}" =~ /\p{Syrc}/,
    'bare short Script alias uses Script_Extensions');
ok("\x{10450}" =~ /\p{  Shavian}/,
    'bare Script accepts leading ASCII loose separators');
ok("\x{10450}" =~ /\p{-_Shaw}/,
    'bare short Script accepts mixed leading separators');
ok("\x{1DFA}" =~ /\p{Is_Syriac}/,
    'Is-prefixed long Script alias resolves');
ok("\x{1DFA}" =~ /\p{--Is_Syrc}/,
    'Is-prefixed short Script alias preserves loose spelling');

ok('A' =~ /\p{IsAlpha}/, 'binary alias retains precedence over Script');
ok('A' =~ /\p{IsL}/, 'General_Category retains precedence over Script');
ok("\x{03B1}" =~ /\p{IsGreek}/, 'Script wins its ordinary Is shortcut');
ok("\x{03B1}" =~ /\p{Is_Script=Greek}/,
    'exact Is property assignment remains separate');

ok(!eval(q{qr/\p{Is_Script=:\ASyriac\z:}/; 1}),
    'Is-prefixed Script wildcard remains rejected');
{
    local $SIG{__WARN__} = sub { };
    ok(!eval(qq{qr/\\p{Is\x{A0}Syriac}/; 1}),
        'non-ASCII whitespace is not treated as a loose separator');
}

done_testing;
