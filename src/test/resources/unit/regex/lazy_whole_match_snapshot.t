use strict;
use warnings;
use Test::More;

my $subject = 'pre-MATCH-post';
ok($subject =~ /(MATCH)/, 'ordinary match succeeds');
$subject = 'changed';
ok('miss' !~ /absent/, 'later failed match leaves match variables intact');
is($&, 'MATCH', 'whole-match text keeps the successful match-time subject');
is($1, 'MATCH', 'capture keeps the successful match-time subject');

my $replacement_subject = 'abc';
$replacement_subject =~ s/(b)/do {
    is($&, 'b', 'whole-match text is available during replacement evaluation');
    'B';
}/e;
is($replacement_subject, 'aBc', 'replacement result');
is($&, 'b', 'whole-match text survives replacement evaluation');

done_testing;
