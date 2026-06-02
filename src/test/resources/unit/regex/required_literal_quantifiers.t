use Test::More tests => 19;

ok('abbbbc' =~ /.{1}/, 'wildcard quantifier count is not treated as a required literal');
is($&, 'a', 'single-character wildcard match still records $&');

ok('abbbbc' =~ /.{3,4}/, 'range quantifier count is not treated as a required literal');
is($&, 'abbb', 'range wildcard match still records $&');

ok(chr(0) =~ /[[:cntrl:]]/a, 'POSIX character class closing bracket is not treated as a required literal');
ok(chr(0) !~ /[[:graph:]]/a, 'POSIX graph class still rejects NUL');
ok('!' =~ /[[:graph:]]/a, 'POSIX graph class still matches printable punctuation');
ok(chr(0x80) =~ /\pC/, 'single-letter Unicode property is not treated as a required literal');
ok('A' =~ /\PC/, 'negated single-letter Unicode property matches non-control characters');
ok(chr(1) =~ /\cA/, 'control escape payload is not treated as a required literal');
ok(chr(0x100) =~ /\400/, 'octal escape payload is not treated as a required literal');
ok("abcabc" =~ /(a)(b)(c)\g1\g2\g3/, 'numeric \\g payload is not treated as a required literal');
ok("b\x81" =~ /([[:ascii:]]+)\x81/, 'hex escape payload is not treated as a required literal');
ok("" =~ /^a{,2}$/, 'empty-min quantifier is not treated as a required literal');
ok("aa" =~ /^a{,2}$/, 'empty-min quantifier still matches its upper bound');
ok("a" =~ /.{, 2 }/, 'spaced empty-min wildcard quantifier is not treated as a required literal');
ok("a" =~ /\p{Latin}{ , 2 }/, 'spaced empty-min property quantifier is not treated as a required literal');
ok("2" =~ /(?[ ( ( \pN & ( [a] + [2] ) ) ) ])/, 'extended character class set syntax is not prefiltered as literal source text');
my $digit_set = qr/(?[ [2] ])/;
ok("2" =~ /(?[ \pN & $digit_set ])/, 'interpolated extended character class is not prefiltered as literal source text');
