use strict;
use warnings;
use Test::More;
no warnings 'experimental::regex_sets';

my $byte_upper = chr 0xC0;
my $byte_lower = chr 0xE0;
my $byte_positive = qr/(?d:[$byte_lower\x{5f}])/i;
my $byte_negative = qr/(?d:[^$byte_lower])/i;

ok($byte_upper !~ $byte_positive,
    'byte-backed default class does not fold Latin-1');
ok($byte_upper =~ $byte_negative,
    'byte-backed negated class keeps the Latin-1 sibling');
ok('A' =~ /(?d:[a_])/i,
    'byte-backed default class still folds ASCII');
ok('ss' !~ /(?d:[$byte_upper\x{DF}_])/i,
    'byte-backed default class does not expand a non-ASCII multi-fold');
ok($byte_upper =~ /(?d:\p{Lowercase})/i,
    'byte-backed Unicode property retains a Latin-1 simple fold');
ok($byte_upper =~ /(?d:[\p{Lowercase}_])/i,
    'composed byte-backed Unicode property retains a Latin-1 simple fold');
ok($byte_upper !~ /(?d:[^\p{Lowercase}])/i,
    'negated byte-backed Unicode property excludes its simple-fold sibling');
ok($byte_upper !~ /(?d:\P{Lowercase})/i,
    'complemented byte-backed Unicode property excludes its fold sibling');
ok($byte_upper !~ /(?d:[\P{Lowercase}_])/i,
    'composed complemented property excludes its fold sibling');
ok('ss' =~ /(?d:\p{Lowercase})/i,
    'byte-backed Unicode property retains a non-ASCII multi-fold');

my $unicode_upper = chr 0xC0;
my $unicode_lower = chr 0xE0;
utf8::upgrade($unicode_upper);
utf8::upgrade($unicode_lower);
my $unicode_positive = qr/(?d:[$unicode_lower\x{5f}])/i;
my $unicode_negative = qr/(?d:[^$unicode_lower])/i;

ok($unicode_upper =~ $unicode_positive,
    'upgraded default class folds Latin-1');
ok($unicode_upper !~ $unicode_negative,
    'upgraded negated class excludes the folded sibling');
ok('ss' =~ /(?d:[$unicode_upper\x{DF}_])/i,
    'upgraded default class retains a non-ASCII multi-fold');

ok(chr(0xFF) =~ /(?d:[\x{178}_])/i,
    'an above-byte class promotes a byte subject to Unicode folding');
ok('K' =~ /(?d:[\x{212A}_])/i,
    'an above-byte class retains an ASCII-crossing Unicode fold');

ok($byte_upper =~ /(?d:(?[ \p{Lowercase} + [_] ]))/i,
    'property union retains property fold provenance');
ok($byte_upper =~ /(?d:(?[ \p{Lowercase} & [\x{e0}] ]))/i,
    'property intersection retains surviving property provenance');
ok($byte_upper =~ /(?d:(?[ \p{Lowercase} - [a] ]))/i,
    'property subtraction retains surviving property provenance');
ok($byte_upper !~ /(?d:(?[ [\x{e0}] - \p{Uppercase} ]))/i,
    'literal subtraction does not inherit unrelated property provenance');
ok($byte_upper !~
        /(?d:(?[ ([\x{e0}] + \p{Uppercase}) - \p{Uppercase} ]))/i,
    'removed property source does not promote a surviving literal sibling');
ok($byte_upper =~ /(?d:(?[ \p{Lowercase} ^ [a] ]))/i,
    'property symmetric difference retains unrelated property provenance');
ok($byte_upper !~ /(?d:(?[ ! \p{Lowercase} ]))/i,
    'property complement excludes its folded sibling');
ok($byte_upper =~ /(?d:(?[ ! ! \p{Lowercase} ]))/i,
    'double property complement restores its folded sibling');
ok($byte_lower =~
        /(?d:(?[ ([\x{e0}] + \p{Hex_Digit}) - \p{Hex_Digit} ]))/i,
    'literal survives removal of disjoint property sources');
ok($byte_upper =~
        /(?d:(?[ ([\x{e0}] + \p{Hex_Digit}) - \p{Hex_Digit} ]))/i,
    'surviving extended-set literal keeps its Perl fold sibling');

done_testing;
