use strict;
use warnings;
use Test::More tests => 18;

{
    my $target = q{a.b[0]$|(){}*+?^-};
    ok($target =~ /\Qa.b[0]$|(){}*+?^-\E/, 'raw quote region matches literally');
    is($&, $target, 'raw quote region consumes the complete literal text');
}

{
    ok('before.*[x]' =~ /before\Q.*[x]/,
        'unterminated raw quote extends to pattern end');
    ok('a.bc[d]' =~ /\Qa.b\Ec\Q[d]\E/, 'adjacent raw quote regions compose');
}

{
    my $subject = '012-AbC-6789';
    ok($subject =~ /(?ip:abc)/, 'accepted scoped inline p matches');
    is(${^PREMATCH}, '012-', 'scoped inline p retains prematch');
    is(${^MATCH}, 'AbC', 'scoped inline p retains match');
    is(${^POSTMATCH}, '-6789', 'scoped inline p retains postmatch');
}

{
    my $qr = qr/(?p:345)/;
    my $subject = '012-345-6789';
    ok($subject =~ $qr, 'compiled qr preserves parser-owned inline p');
    is(${^MATCH}, '345', 'compiled qr exposes retained match');

    ok($subject =~ //, 'empty-pattern reuse retains inline p');
    is(${^MATCH}, '345', 'empty-pattern reuse exposes retained match');
}

{
    my $literal = '(?p)';
    ok($literal =~ /\(\?p\)/, 'escaped inline-p spelling remains literal');
    ok(!defined ${^MATCH}, 'escaped spelling does not retain match variables');

    ok('p' =~ /[(?p)]/, 'character-class inline-p spelling remains literal');
    ok(!defined ${^MATCH}, 'character-class spelling does not retain variables');
}

{
    my $subject = 'abc';
    ok($subject =~ /(?-p:abc)/, 'negative inline p remains accepted');
    ok(!defined ${^MATCH}, 'negative inline p does not retain match variables');
}
