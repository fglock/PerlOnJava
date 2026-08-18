use strict;
use warnings;
use Test::More tests => 18;
no warnings 'experimental::uniprop_wildcards';

ok('A' =~ /\p{Gc=Lu}/, 'short property and value aliases');
ok('A' =~ /\p{General_Category=Uppercase_Letter}/, 'long aliases');
ok('A' =~ /\p{Category=uppercaseletter}/, 'Perl Category property alias');
ok('A' =~ /\p{Gc: Lu}/, 'colon property delimiter');
ok('A' =~ /\p{Is_General_Category=Lu}/, 'Is property prefix');
ok('A' =~ /\p{Gc=:\ALu\z:}/, 'anchored wildcard value');
ok('A' =~ /\p{General_Category= --Uppercase_letter}/,
    'loose hyphen, underscore, and whitespace matching');
ok('A' =~ /\p{Gc=L}/, 'composite Letter category');
ok('A' =~ /\p{Gc=LC}/, 'composite Cased_Letter category');
ok('1' =~ /\p{Gc=N}/, 'composite Number category');
ok('!' =~ /\p{Gc=P}/, 'composite Punctuation category');
ok('$' =~ /\p{Gc=S}/, 'composite Symbol category');
ok("\n" =~ /\p{Gc=C}/, 'composite Other category');
ok('a' !~ /\p{Gc=Lu}/, 'exact category excludes other letters');
ok('a' =~ /\P{Gc=Lu}/, 'outer property negation');
ok('a' =~ /\p{^Gc=Lu}/, 'inner caret property negation');
ok('A' =~ /\P{^Gc=Lu}/, 'double property negation');
ok("\x{301}" =~ /\p{General_Category=Nonspacing_Mark}/,
    'nonspacing mark long value');
