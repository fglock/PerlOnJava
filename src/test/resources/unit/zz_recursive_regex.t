use strict;
use warnings;
use utf8;
use Test::More tests => 12;

my $balanced = qr/(\((?:(?>[^()]+)|(?-1))*\))/;

ok('(abc)' =~ /^$balanced$/, 'recursive pattern matches one level');
ok('(a(b)c)' =~ /^$balanced$/, 'recursive pattern matches nested input');
ok('(a(b)c' !~ /^$balanced$/, 'recursive pattern rejects unbalanced input');

'before (a(b)c) after' =~ /$balanced/;
is($1, '(a(b)c)', 'recursive capture preserves the complete match');

my $unicode = "é(a(b)c)z";
ok($unicode =~ /$balanced/, 'recursive pattern finds a match after a wide character');
is($-[1], 1, 'capture start is a Perl character offset');
is($+[1], 8, 'capture end is a Perl character offset');

my $deep = ('(' x 500) . 'x' . (')' x 500);
ok($deep =~ /^$balanced$/, 'recursive pattern uses a stack-safe backend');

my $many = '(a) (b(c))';
my @matches;
push @matches, $1 while $many =~ /$balanced/g;
is_deeply(\@matches, ['(a)', '(b(c))'], 'global recursive matching advances correctly');

my $replaced = '(a) and (b(c))';
my $count = ($replaced =~ s/$balanced/X/g);
is($count, 2, 'global recursive substitution reports its count');
is($replaced, 'X and X', 'global recursive substitution replaces complete matches');

my $insensitive = qr/(\((?:(?>[^()]+)|(?-1))*\))/i;
ok('(A(b)C)' =~ /^$insensitive$/, 'recursive backend preserves regex flags');
