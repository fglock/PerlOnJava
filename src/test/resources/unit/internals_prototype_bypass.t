use strict;
use warnings;
use Test::More;

my @cases = (
    [ 'SvREADONLY',             'SCALAR[, ON]' ],
    [ 'SvREFCNT',               'SCALAR[, REFCOUNT]' ],
    [ 'hv_clear_placeholders',  'hv' ],
);

for my $argument ('', 'q[]', '1', 'undef') {
    for my $case (@cases) {
        my ($name, $signature) = @{$case};
        my $result = eval "&Internals::$name($argument)";
        like(
            $@,
            qr{\AUsage: Internals::\Q$name\E\(\Q$signature\E\) at \(eval \d+\) line 1\.\n\z},
            "&Internals::$name($argument) reports its prototype usage",
        );
    }
}

my @empty;
is eval { Internals::SvREFCNT(@empty, 9); $@ }, '',
    'SvREFCNT accepts an empty aggregate followed by its optional refcount';

done_testing;
