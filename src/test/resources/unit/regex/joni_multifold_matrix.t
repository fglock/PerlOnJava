use strict;
use warnings;
use utf8;
use Test::More tests => 33;

ok("ss" =~ /^\x{00DF}$/iu, 'sharp s forward');
ok("\x{00DF}" =~ /^ss$/iu, 'sharp s reverse');
ok("\x{017F}\x{017F}" =~ /^\x{00DF}$/iu, 'sharp s through long s');
ok("st" =~ /^[\x{FB06}]$/iu, 'ligature class forward');
ok("\x{FB06}" =~ /^st$/iu, 'ligature reverse');
ok("ffi" =~ /^\x{FB03}$/iu, 'three-codepoint ligature forward');
ok("\x{FB03}" =~ /^ffi$/iu, 'three-codepoint ligature reverse');
ok("\x{01F0}" =~ /^\x{006A}\x{030C}$/iu, 'two-codepoint forward');
ok("\x{006A}\x{030C}" =~ /^\x{01F0}$/iu, 'two-codepoint reverse');
ok("\x{0390}" =~ /^\x{03B9}\x{0308}\x{0301}$/iu, 'three-codepoint forward');
ok("\x{03B9}\x{0308}\x{0301}" =~ /^\x{0390}$/iu, 'three-codepoint reverse');
ok("\x{1E9E}" =~ /^\x{00DF}$/iu, 'capital sharp s sibling forward');
ok("\x{00DF}" =~ /^\x{1E9E}$/iu, 'capital sharp s sibling reverse');

ok("xssy" =~ /^x(?i:\x{00DF})y$/u, 'scoped i enables full fold');
ok("xssy" !~ /^x(?-i:\x{00DF})y$/iu, 'scoped minus i disables full fold');
ok("\x{017F}s\x{017F}" =~ /^(?i:s)(?iaa:s)(?i:s)$/u,
    'scoped aa preserves outer fold state');

ok("\x{00DF}" =~ /^ss$/ia, 'a allows Unicode to ASCII full fold');
ok("ss" =~ /^\x{00DF}$/ia, 'a allows ASCII to Unicode full fold');
ok("\x{00DF}" !~ /^ss$/iaa, 'aa blocks Unicode to ASCII full fold');
ok("ss" !~ /^\x{00DF}$/iaa, 'aa blocks ASCII to Unicode full fold');
ok("Ä" =~ /^ä$/iaa, 'aa preserves non-ASCII simple fold');
ok("ffi" !~ /^\x{FB03}$/iaa, 'aa blocks three-codepoint ASCII ligature fold');
ok("st" !~ /^\x{FB06}$/iaa, 'aa blocks two-codepoint ASCII ligature fold');
ok("\x{03B9}\x{0308}\x{0301}" =~ /^\x{0390}$/iaa,
    'aa preserves all-non-ASCII multi fold');

ok("\x{212A}" =~ /^[k]$/iu, 'positive class gains Kelvin fold');
ok("\x{212A}" !~ /^[^k]$/iu, 'negative class excludes Kelvin fold');
ok("x" =~ /^[^k]$/iu, 'negative class keeps unrelated member');
ok("st" =~ /^[\x{FB06}]$/iu, 'positive class expands multi fold');
ok("\x{FB06}" !~ /^[^\x{FB06}]$/iu, 'negative class excludes source member');

my $set = qr/(?[ [ksä] - [x] ])/i;
ok("k" =~ /^$set$/u, 'composed set keeps first member');
ok("s" =~ /^$set$/u, 'composed set keeps second member');
ok("ä" =~ /^$set$/u, 'composed set keeps non-ASCII member');
ok("x" !~ /^$set$/u, 'composed set subtraction remains excluded');
