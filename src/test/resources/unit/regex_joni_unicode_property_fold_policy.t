use strict;
use warnings;
use utf8;
use Test::More tests => 8;

ok("A" =~ /\p{gc=Uppercase_Letter}/, 'general category positive');
ok("a" !~ /\p{gc=Uppercase_Letter}/, 'general category negative');
ok("é" =~ /\p{Script=Latin}/, 'script property in pinned Perl data');
ok("a" =~ /\p{gc=Uppercase_Letter}/i,
    'general category participates in case folding');
ok("A" !~ /\P{gc=Uppercase_Letter}/i,
    'negated category complements after case folding');
ok("\x{212A}" !~ /\p{Block=ASCII}/i,
    'block membership does not gain case-fold members');
ok("K" !~ /\p{Script=Common}/i,
    'script membership does not gain case-fold members');
ok("k" =~ /\p{gc=Uppercase_Letter}/i,
    'general-category membership gains case-fold members');
