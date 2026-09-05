use strict;
use warnings;
use Test::More tests => 5;

my $result = eval q{
    eval { goto missing_label };
    [ $@, 42 ];
};
my $outer_error = $@;

like($result->[0], qr/Can't find label missing_label/,
    'eval BLOCK catches a missing goto label inside eval STRING');
is($result->[1], 42, 'eval STRING continues after the inner eval');
is($outer_error, '', 'the control-flow error does not escape to the outer eval');

my ($loop_error, $entered_loop);
eval {
    eval { goto inside_loop };
    $loop_error = $@;
    last;
    foreach my $item (1) {
        inside_loop:
        $entered_loop = 1;
    }
};
like($loop_error, qr/Can't "goto" into the middle of a foreach loop/,
    'nested eval cannot jump into an unentered foreach loop');
ok(!$entered_loop, 'illegal goto does not enter the loop body');
