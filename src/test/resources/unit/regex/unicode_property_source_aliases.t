use strict;
use warnings;
use Test::More;

# Literal patterns take the source-compilation path; eval qr// exercises a
# different resolver lifecycle and would miss aliases being deferred as
# user-defined properties.
ok("x " =~ /^[\p{IsPrint}\s]*$/,
    'literal IsPrint combines with whitespace during compilation');
ok("x" =~ /\p{IsWord}/,
    'literal IsWord is recognized as a built-in during compilation');
ok("!" !~ /\p{IsWord}/,
    'literal IsWord excludes punctuation');

done_testing;
