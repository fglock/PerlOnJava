use strict;
use warnings;
use utf8;
use feature 'unicode_eval';
no warnings 'experimental::regex_sets';
use Test::More;

ok("\x{03A9}" =~ /\p{Script=Greek}/, 'Script matches outside a class');
ok('A' !~ /\p{Script=Greek}/, 'Script excludes another script');
ok("\x{30FC}" =~ /\p{Script_Extensions=Hiragana}/,
    'Script_Extensions matches outside a class');
ok("\x{03A9}" =~ /[\p{Block=Greek and Coptic}]/,
    'Block matches in a standard class');
ok('A' =~ /\p{Age=1.1}/, 'Age matches assigned scalar');
ok("\x{20AC}" !~ /\p{Present_In=1.1}/,
    'Present_In excludes a later scalar');
ok('A' =~ /\p{PerlWord}/, 'Perl binary alias matches');
ok('!' !~ /\p{PerlWord}/, 'Perl binary alias excludes punctuation');
ok('1' =~ /\p{Alphabetic=No}/, 'false binary assignment matches complement');
ok('A' !~ /\p{Alphabetic=No}/, 'false binary assignment excludes base set');
ok('a' =~ /\p{Uppercase}/i, '/i applies the property fold policy');
ok('a' =~ /[\p{Uppercase}]/i, '/i applies inside standard classes');
ok("\x{03A9}" =~ /(?[ \p{Script=Greek} & \p{Block=Greek and Coptic} ])/,
    'raw properties compose in an extended class');
ok('A' !~ /(?[ \p{Script=Greek} & \p{Block=Greek and Coptic} ])/,
    'extended intersection excludes nonmembers');
{
    use bytes;
    ok('A' =~ /\p{Script=Latin}/, 'Script resolves in byte mode');
    ok("\xE9" =~ /[\p{Script=Latin}]/, 'Script class resolves high byte');
    ok('1' =~ /\p{Alphabetic=No}/, 'false binary assignment resolves in byte mode');
    ok('a' =~ /\p{Uppercase}/i, 'property fold policy resolves in byte /i mode');
}

done_testing;
