use strict;
use warnings;
use Test::More tests => 16;

our ($REGMARK, $REGERROR);
local $REGMARK;
local $REGERROR;

ok('ac' =~ /a(*MARK:first)b|a(*MARK:second)c/, 'MARK allows ordinary success');
is($REGMARK, 'second', 'successful path publishes its most recent MARK');
is($REGERROR, '', 'successful match clears REGERROR');

ok('ac' !~ /a(*MARK:failed)(*FAIL)/, 'MARK path can fail');
is($REGMARK, '', 'failed match clears REGMARK');
is($REGERROR, 'failed', 'failed MARK path publishes REGERROR');

ok('aaaab' !~ /a+b(*PRUNE:blocked)(*FAIL)/, 'named PRUNE can fail');
is($REGERROR, 'blocked', 'named PRUNE publishes its name');

ok('aaaab' !~ /a+b(*PRUNE)(*FAIL)/, 'unnamed PRUNE can fail');
is($REGERROR, '1', 'unnamed PRUNE publishes one');

ok('ab' =~ /a(*MARK:skip-target)b(*SKIP:skip-target)(*FAIL)|b/,
    'named SKIP permits a later match');
is($-[0], 1, 'named SKIP resumes searching at its matching MARK');

our @callback_marks;
ok('foofoo' =~ /foo(*MARK:callback)(?{push @callback_marks, $REGMARK})/,
    'MARK remains visible inside a regex callback');
is_deeply(\@callback_marks, ['callback'], 'callback observes provisional REGMARK');

{
    package RegexMarkOther;
    our ($REGMARK, $REGERROR);
    local $REGMARK;
    local $REGERROR;
    ::ok('ac' =~ /a(*MARK:package)c/, 'MARK works in another package');
    ::is($REGMARK, 'package', 'REGMARK is package-local');
}
