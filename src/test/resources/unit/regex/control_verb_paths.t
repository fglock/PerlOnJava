use strict;
use warnings;
use Test::More tests => 10;

our $count = 0;
'aaab' =~ /a+b?(*SKIP)(?{$count++})(*FAIL)/;
is($count, 1, 'unnamed SKIP cuts the current search start');

local $_ = 'aaabaaab';
$count = 0;
our @seen = ();
1 while /(a+b?)(*MARK:foo)(*SKIP)(?{$count++; push @seen, $1})(*FAIL)/g;
is($count, 2, 'adjacent unnamed SKIP visits each selected path once');
is_deeply(\@seen, [qw(aaab aaab)], 'SKIP preserves captures for callbacks');

$_ = 'aaabaaab';
$count = 0;
@seen = ();
1 while /(a*(*MARK:a)b?)(*MARK:x)(*SKIP:a)(?{$count++; push @seen, $1})(*FAIL)/g;
is($count, 5, 'named SKIP resumes at its matching MARK');
is_deeply(\@seen, ['aaab', 'b', 'aaab', 'b', ''],
    'named SKIP retains Perl search progression and captures');

our ($REGMARK, $REGERROR);
local $REGMARK;
local $REGERROR;
for my $word (qw(bar baz bop)) {
    $REGERROR = '';
    "aaaaa$word" =~
        /a+(?:bar(*COMMIT:bar)|baz(*COMMIT:baz)|bop(*COMMIT:bop))(*FAIL)/;
    is($REGERROR, $word, "named COMMIT publishes $word after failure");
}

ok('ABC' =~ /A(*THEN)X|B(*THEN)C/, 'THEN enters the later top-level alternative');
ok('BAX' =~ /A(*THEN)X|B(*THEN)C/, 'THEN preserves later search starts');
