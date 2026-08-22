use strict;
use warnings;
use Test::More;

sub capture_names {
    my @names;
    for my $level (0 .. 8) {
        my @frame = caller($level);
        last unless @frame;
        push @names, $frame[3];
    }
    return @names;
}

my @captured;
package Caller::NestedEval;
sub warner {
    eval {
        sub {
            eval { @captured = main::capture_names() };
        }->();
    };
}
sub wcaller { warner() }

package main;
Caller::NestedEval::wcaller();
is_deeply(
    \@captured,
    [
        'main::capture_names',
        '(eval)',
        'Caller::NestedEval::__ANON__',
        '(eval)',
        'Caller::NestedEval::warner',
        'Caller::NestedEval::wcaller',
    ],
    'caller preserves nested eval and named interpreter frames',
);

done_testing;
