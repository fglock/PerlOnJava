use strict;
use warnings;
use utf8;
use Test::More;

my $basic = 'abcdef';
ok($basic =~ /abc\Kdef/, 'KEEP pattern matches');
is($&, 'def', 'KEEP resets the complete match');
is($-[0], 3, 'KEEP resets match start');
is($+[0], 6, 'KEEP retains match end');
is($`, 'abc', 'prematch ends at the KEEP position');
is($', '', 'postmatch starts at the native match end');

my $captured = 'abc';
ok($captured =~ /(a)(b)\K(c)/, 'captures span KEEP');
is($&, 'c', 'complete match begins after KEEP with captures');
is_deeply([$1, $2, $3], [qw(a b c)], 'capture numbering excludes KEEP');
is_deeply([$-[1], $+[1], $-[2], $+[2], $-[3], $+[3]],
    [0, 1, 1, 2, 2, 3], 'capture offsets remain physical subject offsets');

my @global = 'a1b2' =~ /[a-z]\K\d/g;
is_deeply(\@global, [qw(1 2)], 'global list match returns post-KEEP matches');

my $global_scalar = 'a1b2';
ok($global_scalar =~ /[a-z]\K\d/g, 'first scalar global KEEP match succeeds');
is($&, '1', 'first scalar global match value');
is(pos($global_scalar), 2, 'first scalar global position uses match end');
ok($global_scalar =~ /[a-z]\K\d/g, 'second scalar global KEEP match succeeds');
is($&, '2', 'second scalar global match value');
is(pos($global_scalar), 4, 'second scalar global position uses match end');

my $substitution = 'foobar';
is($substitution =~ s/foo\Kbar/X/, 1, 'single KEEP substitution count');
is($substitution, 'fooX', 'single KEEP substitution preserves prefix');

my $global_substitution = 'a1b2';
is($global_substitution =~ s/[a-z]\K\d/X/g, 2,
    'global KEEP substitution count');
is($global_substitution, 'aXbX',
    'global KEEP substitution preserves every prefix');

my $capture_substitution = 'abc';
$capture_substitution =~ s/(a)(b)\K(c)/<$1$2$3>/;
is($capture_substitution, 'ab<abc>',
    'KEEP substitution retains captures before the reset');

my $backtracking = 'abcx';
ok($backtracking =~ /ab\Kc(?:z|x)/, 'KEEP survives successful backtracking');
is($&, 'cx', 'backtracked match retains the final KEEP start');

my $failed_keep_branch = 'ax';
ok($failed_keep_branch =~ /(?:a\Kb|a)x/,
    'failed KEEP branch restores the prior match start');
is($&, 'ax', 'failed KEEP branch does not leak its reset');
is($-[0], 0, 'failed KEEP branch restores offset zero');

ok('abc' =~ /a\Kb\Kc/, 'multiple KEEP assertions match');
is($&, 'c', 'last successful KEEP assertion wins');
ok('abc' =~ /abc\K/, 'terminal KEEP permits an empty match');
is($&, '', 'terminal KEEP resets to an empty complete match');
is($-[0], 3, 'terminal KEEP start is the input end');

my $unicode = "préfixe";
ok($unicode =~ /pré\Kfixe/, 'Unicode KEEP pattern matches');
is($&, 'fixe', 'Unicode KEEP reports character-aligned match text');
is($-[0], 3, 'Unicode KEEP reports Perl character offset');

{
    use bytes;
    my $bytes = pack('C*', 0x61, 0x62, 0x63, 0x64);
    ok($bytes =~ /ab\Kcd/, 'byte input KEEP pattern matches');
    is($&, 'cd', 'byte input KEEP reports post-reset bytes');
    is($-[0], 2, 'byte input KEEP reports byte offset');
}

{
    no warnings 'regexp';
    ok('\\K' =~ /\A\\K\z/, 'escaped backslash K remains literal');
    ok('K' =~ /\A[\K]\z/, 'K inside a class remains a class member');
    ok('\\K' =~ /\A\Q\K\E\z/, 'K inside quote-meta remains literal');
}

my $lookahead = 'ab(?=c\\Kd)';
eval { qr/$lookahead/ };
like($@, qr/\\K not permitted in lookahead\/lookbehind/,
    'KEEP in lookahead remains a compile error');

my $lookbehind = '(?<=a\\Kb)c';
eval { qr/$lookbehind/ };
like($@, qr/\\K not permitted in lookahead\/lookbehind/,
    'KEEP in lookbehind remains a compile error');

done_testing;
