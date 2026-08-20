use strict;
use warnings;
use Test::More tests => 4;

for my $case (
    [q{qr/(*DOOF)/}, q{Unknown verb pattern 'DOOF'}],
    [q{qr/(*script_runfoo)/}, q{Unknown '(*...)' construct 'script_runfoo'}],
    [q{qr/(*script_run)/}, q{'(*script_run' requires a terminating ':'}],
    [q{qr/(*sr)/}, q{'(*sr' requires a terminating ':'}],
) {
    my $compiled = eval "$case->[0]; 1";
    ok(!$compiled && index($@, $case->[1]) >= 0, 'control verb uses Perl diagnostic');
}
