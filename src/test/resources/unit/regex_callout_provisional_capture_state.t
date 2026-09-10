use strict;
use warnings;
use Test::More;
use re 'eval';

my ($named, $numbered, $start, $end);
ok 'ok' =~ /(?<word>ok)(?{
    $named = $+{word};
    $numbered = $1;
    $start = $-[1];
    $end = $+[1];
})/, 'dynamic callback matches';

is $named, 'ok', 'dynamic callback sees its named capture';
is $numbered, 'ok', 'dynamic callback sees its numbered capture';
is $start, 0, 'dynamic callback sees capture start';
is $end, 2, 'dynamic callback sees capture end';

done_testing;
