use strict;
use warnings;
use Test::More tests => 35;
use lib 'src/test/resources/unit/lib';
use Phase36Cname;
no warnings 'experimental::regex_sets';

ok('a' =~ /(?[ [a] + [b] ])/ && 'b' =~ /(?[ [a] + [b] ])/,
   'extended-class union');
ok('b' =~ /(?[ [a-c] & [b-d] ])/ && 'a' !~ /(?[ [a-c] & [b-d] ])/,
   'extended-class intersection');
ok('a' =~ /(?[ [a-c] - [b] ])/ && 'b' !~ /(?[ [a-c] - [b] ])/,
   'extended-class subtraction');
ok('a' =~ /(?[ [a-b] ^ [b-c] ])/ && 'b' !~ /(?[ [a-b] ^ [b-c] ])/,
   'extended-class symmetric difference');
ok('z' =~ /(?[ ! [a] ])/ && 'a' !~ /(?[ ! [a] ])/,
   'extended-class complement');
ok('b' =~ /(?[ ([a] + [b]) & [b-c] ])/,
   'extended-class grouping');

my $nested = qr/(?[ [b] & [b-c] ])/;
ok('b' =~ /(?[ [a] + $nested ])/,
   'interpolated extended-class nesting');
ok('A' =~ /(?[ \N{EVIL} ])/,
   'named character operand');
ok('5' =~ /(?[ \p{Digit} & [0-9] ])/,
   'property operand');
ok("\t" =~ /(?[ \t ])/,
   'escaped character operand');
ok('c' =~ /(?[ [a-c] # comment
                    - [a-b] ])/,
   'extended whitespace and comments');
ok('#' =~ /(?[[#]])/, 'hash remains literal in a nested ordinary class');
ok('#' =~ /(?[ \# ])/, 'escaped hash remains an operand');

my $empty_error = eval q{ qr/(?[[\N{EMPTY-STR}]])/; 1 } ? '' : $@;
like($empty_error, qr/Zero length \\N\{\}/,
     'empty named-character result is illegal');
my $multi_error = eval q{ qr/(?[[\N{LONG-STR}]])/; 1 } ? '' : $@;
like($multi_error, qr/restricted to one character/,
     'multi-code-point named-character result is illegal');

ok('A' =~ /(?[ [a] + [x] ])/i, 'union folds operands');
ok('a' =~ /(?[ [a] & [A] ])/i, 'intersection folds each operand');
ok('A' =~ /(?[ [a] & [A] ])/i, 'intersection result remains folded');
ok('a' !~ /(?[ [a] - [A] ])/i, 'subtraction folds each operand');
ok('A' !~ /(?[ [a] - [A] ])/i, 'subtraction is empty after folding');
ok('A' !~ /(?[ ! [a] ])/i, 'complement excludes folded sibling');

no warnings 'non_unicode';
my $wide = chr(0x110000);
ok($wide =~ /(?[ [\x{110000}] + [a] ])/,
   'wide scalar survives union');
ok($wide !~ /(?[ ! [\x{110000}] ])/,
   'wide scalar survives complement');

my $scoped_default = qr/(?[ [a] ])/;
ok('a' =~ /(?[ $scoped_default ])/i
       && 'A' !~ /(?[ $scoped_default ])/i,
   'scoped default suppresses outer folding');
my $scoped_i = qr/(?[ [a] ])/i;
ok('a' =~ /(?[ $scoped_i ])/
       && 'A' =~ /(?[ $scoped_i ])/,
   'scoped ignore-case folds nested extended class');
my $raw_scoped_error = eval q{ qr/(?[ (?i:[a]) ])/; 1 } ? '' : $@;
like($raw_scoped_error,
     qr/Unexpected character|Expecting interpolated extended charclass/,
     'scoped modifiers require an interpolated extended class');

my $ascii_word = qr/(?[ [\w] ])/a;
ok('A' =~ /(?[ $ascii_word ])/ && "\x{e9}" !~ /(?[ $ascii_word ])/,
   'interpolated /a keeps word class ASCII');
my $unicode_word = qr/(?[ [\w] ])/u;
ok('A' =~ /(?[ $unicode_word ])/ && "\x{e9}" =~ /(?[ $unicode_word ])/,
   'interpolated /u keeps Unicode word semantics');
my $default_word = qr/(?[ [\w] ])/d;
ok('A' =~ /(?[ $default_word ])/,
   'interpolated /d preserves default charset syntax');
my $ascii_strict = qr/(?[ [k] ])/iaa;
ok('k' =~ /(?[ $ascii_strict ])/ && 'K' =~ /(?[ $ascii_strict ])/
       && "\x{212a}" !~ /(?[ $ascii_strict ])/,
   'interpolated /iaa blocks Kelvin fold crossing');

my $short_octal_error = eval q{ qr/(?[ \05 ])/; 1 } ? '' : $@;
like($short_octal_error, qr/Need exactly 3 octal digits/,
     'short octal extended operand is rejected');
my $long_octal_error = eval q{ qr/(?[ \0004 ])/; 1 } ? '' : $@;
like($long_octal_error, qr/Need exactly 3 octal digits/,
     'long octal extended operand is rejected');
ok("\005" =~ /(?[ \005 ])/,
   'exactly three octal digits remain a valid operand');

ok('A' =~ /(?[ [[:alpha:]] ])/,
   'POSIX bracket remains a valid operand');
my $nested_class_error = eval q{ qr/(?[[[:x]]])/; 1 } ? '' : $@;
like($nested_class_error,
     qr/Unexpected '\]' with no following '\)' in \(\?\[\.\.\./,
     'malformed nested POSIX class reports the outer boundary error');
