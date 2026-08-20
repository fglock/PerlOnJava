use strict;
use warnings;
use Test::More tests => 4;

my $inside = "\x{80}";
my $outside = 'A';

ok($outside =~ /\p{  ^  In Latin 1 Supplement  }/,
   'inner caret negates a positive property after whitespace');
ok($inside =~ /\P{  ^  In Latin 1 Supplement  }/,
   'outer P and inner caret cancel after whitespace');
ok($outside =~ /\p{  ^  In Latin 1 Supplement  }/i,
   'spaced inner caret is preserved under case folding');
ok($outside =~ /[\p{  ^  In Latin 1 Supplement  }]/,
   'spaced inner caret works inside a character class');
