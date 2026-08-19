use strict;
use warnings;
use Test::More tests => 14;

ok('A' =~ /^(?(DEFINE)(?<never>FAIL))A$/,
   'DEFINE container does not execute on the main path');
ok('FAIL' !~ /^(?(DEFINE)(?<never>FAIL))$/,
   'definition body cannot match without a call');
ok('word' =~ /^(?&word)(?(DEFINE)(?<word>[a-z]+))$/,
   'named call binds to a following definition');
ok('a' =~ /^(?(DEFINE)(a))(?1)$/,
   'absolute numbered call binds to a definition');

ok('xw' =~ /^(x)(?(DEFINE)(y)(?<z>z))(w)$/,
   'captures after DEFINE retain lexical numbering');
is($1, 'x', 'capture before DEFINE keeps number 1');
ok(!defined($2) && !defined($3) && $4 eq 'w',
   'uncalled definitions stay undefined and following capture is number 4');

ok('b' =~ /^(?(DEFINE)(a)(b))(?-1)$/,
   'relative call binds from its lexical position');
ok('((x))' =~ /^(?&par)(?(DEFINE)(?<par>\((?:x|(?&par))*\)))$/,
   'named definition recurses');
ok('abc' =~ /^(?&piece)c(?(DEFINE)(?<piece>a|ab))$/,
   'called definition backtracks into a later alternative');

ok('ab' =~ /^(?&capturing)(?(DEFINE)(?<capturing>(a)b))$/,
   'capturing definition matches when called');
ok(!defined($1) && !defined($2) && !defined($+{capturing}),
   'subroutine-local captures are restored after return');

my $missing = eval q{ qr/(?&missing)(?(DEFINE)(?<x>x))/; 1 } ? '' : $@;
like($missing, qr/Reference to nonexistent named group/,
     'missing named definition is diagnosed');
my $branch = eval q{ qr/(?(DEFINE)(?<x>x)|y)/; 1 } ? '' : $@;
like($branch, qr/does not allow branches/,
     'DEFINE container rejects top-level alternatives');
