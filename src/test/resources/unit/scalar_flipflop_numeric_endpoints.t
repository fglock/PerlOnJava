use v5.36;
use feature 'smartmatch';
no warnings 'experimental::smartmatch';
use Test::More;

$. = 0;
ok !(21..30 ~~ undef),
    'a numeric scalar flip-flop endpoint compares against the input line number';

my $flip = sub { 21..30 };

$. = 21;
ok $flip->(),
    'the lower numeric endpoint starts the flip-flop at its input line';

$. = 30;
like scalar($flip->()), qr/2E0/,
    'the upper numeric endpoint terminates the flip-flop at its input line';

done_testing;
