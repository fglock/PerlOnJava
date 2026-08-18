use strict;
use warnings;
use Test::More;

is('abbbbc' =~ /\N{1}/ ? $& : undef, 'a',
   '\N{1} is one quantified non-newline character');
is('abbbbc' =~ /\N{3,4}/ ? $& : undef, 'abbb',
   '\N{3,4} is a bounded non-newline quantifier');
is('abbbbc' =~ /\N {3,4}/x ? $& : undef, 'abbb',
   '/x permits whitespace before the non-newline quantifier');
is('abc' =~ /a\Nc/ ? $& : undef, 'abc',
   'plain \N matches a non-newline character');
ok("a\nc" !~ /a\Nc/s,
   'plain \N excludes newline even when dot-all is enabled');
is("foo\n" =~ /\N*\z/ ? $& : undef, '',
   'quantified \N can stop at the terminal newline');
is(' ' =~ /\N{SPACE}/ ? $& : undef, ' ',
   'named-character \N braces remain distinct from quantifier braces');

my $class_error = do {
    local $@;
    eval q{qr/[\N]/};
    $@;
};
like($class_error, qr/\Q\N\E.*character class/i,
     'plain \N remains invalid inside a character class');

done_testing;
