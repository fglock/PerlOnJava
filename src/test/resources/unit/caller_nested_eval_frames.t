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

sub capture_warning_frames {
    my @frames;
    for my $level (0 .. 8) {
        my @frame = caller($level);
        last unless @frame;
        my $uninitialized_enabled = defined($frame[9])
            ? vec($frame[9], $warnings::Offsets{uninitialized}, 1)
            : 0;
        push @frames, [$frame[3], $uninitialized_enabled];
    }
    return @frames;
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

my @ordinary;
package Caller::NestedEvalControl;
sub inner  { @ordinary = main::capture_names() }
sub middle { inner() }
sub outer  { middle() }

package main;
Caller::NestedEvalControl::outer();
is_deeply(
    \@ordinary,
    [
        'main::capture_names',
        'Caller::NestedEvalControl::inner',
        'Caller::NestedEvalControl::middle',
        'Caller::NestedEvalControl::outer',
    ],
    'caller consumes ordinary nested named frames without virtual eval insertion',
);

my @warning_frames;
package Caller::NestedEvalWarningFrames;
sub warner {
    no warnings 'uninitialized';
    eval {
        use warnings 'uninitialized';
        sub {
            no warnings 'uninitialized';
            eval {
                use warnings 'uninitialized';
                @warning_frames = main::capture_warning_frames();
            };
        }->();
    };
}
sub wcaller {
    use warnings 'uninitialized';
    warner();
}

package main;
Caller::NestedEvalWarningFrames::wcaller();
is_deeply(
    \@warning_frames,
    [
        ['main::capture_warning_frames', 1],
        ['(eval)', 0],
        ['Caller::NestedEvalWarningFrames::__ANON__', 1],
        ['(eval)', 0],
        ['Caller::NestedEvalWarningFrames::warner', 1],
        ['Caller::NestedEvalWarningFrames::wcaller', 1],
    ],
    'caller keeps warning masks positionally aligned across virtual eval frames',
);

my @consecutive_eval_frames;
package Caller::ConsecutiveEvalFrames;
sub capture {
    no warnings 'uninitialized';
    eval {
        use warnings 'uninitialized';
        eval {
            no warnings 'uninitialized';
            @consecutive_eval_frames = main::capture_warning_frames();
        };
    };
}

package main;
Caller::ConsecutiveEvalFrames::capture();
is_deeply(
    \@consecutive_eval_frames,
    [
        ['main::capture_warning_frames', 0],
        ['(eval)', 1],
        ['(eval)', 0],
        ['Caller::ConsecutiveEvalFrames::capture', 1],
    ],
    'caller preserves consecutive eval frames and their distinct warning masks',
);

done_testing;
