use strict;
use warnings;
use Test::More tests => 15;

our ($REGMARK, $REGERROR);

{
    local $REGMARK = 'sentinel mark';
    local $REGERROR = 'sentinel error';
    ok('ok' =~ /(*FAIL:backtracked)never|ok/,
        'real control verb can backtrack to ordinary success');
    is($REGMARK, '1', 'controlled success publishes true without a mark');
    is($REGERROR, '', 'controlled success clears a backtracked error');
}

{
    local $REGMARK = 'unnamed mark';
    local $REGERROR = 'unnamed error';
    ok('ok' =~ /ok|(*FAIL)never/,
        'pattern with an untraversed unnamed control verb can succeed');
    is($REGMARK, '1', 'unnamed control-verb metadata publishes true');
    is($REGERROR, '', 'unnamed control-verb metadata clears REGERROR');
}

{
    local $REGMARK = 'quoted mark';
    local $REGERROR = 'quoted error';
    ok('(*FAIL:quoted)' =~ /\Q(*FAIL:quoted)\E/,
        'quoted control-verb spelling is literal');
    is($REGMARK, 'quoted mark', 'quoted spelling leaves REGMARK untouched');
    is($REGERROR, 'quoted error', 'quoted spelling leaves REGERROR untouched');
}

{
    local $REGMARK = 'comment mark';
    local $REGERROR = 'comment error';
    ok('ok' =~ /(?# (*FAIL:commented)ok/,
        'commented control-verb spelling is ignored');
    is($REGMARK, 'comment mark', 'commented spelling leaves REGMARK untouched');
    is($REGERROR, 'comment error', 'commented spelling leaves REGERROR untouched');
}

{
    local $REGMARK = 'ordinary mark';
    local $REGERROR = 'ordinary error';
    ok('ok' =~ /ok/, 'ordinary pattern matches');
    is($REGMARK, 'ordinary mark', 'ordinary match leaves REGMARK untouched');
    is($REGERROR, 'ordinary error', 'ordinary match leaves REGERROR untouched');
}
