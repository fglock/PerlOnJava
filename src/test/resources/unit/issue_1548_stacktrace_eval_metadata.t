use strict;
use warnings;
use Test::More;

our @eval_frames;
our @eval_block_frames;
our $eval_string_caller_name;

sub capture_eval_frames {
    @eval_frames = ();
    for my $level (0 .. 5) {
        my @frame = caller($level);
        last unless @frame;
        push @eval_frames, $frame[6];
    }
}

sub nested_eval {
    eval {
        eval q{main::capture_eval_frames()};
    };
}

sub capture_eval_string_name {
    my @frame = caller(1);
    $eval_string_caller_name = $frame[3];
}

sub call_through_eval_string {
    eval q{main::capture_eval_string_name()};
}

sub capture_eval_block_frames {
    @eval_block_frames = ();
    for my $level (0 .. 6) {
        my @frame = caller($level);
        last unless @frame;
        push @eval_block_frames, [$frame[3], $frame[6]];
    }
}

sub eval_block_subroutine {
    eval { capture_eval_block_frames() };
}

sub call_eval_block_through_string {
    eval q{main::eval_block_subroutine()};
}

nested_eval();
call_through_eval_string();
call_eval_block_through_string();

is_deeply(
    \@eval_frames,
    [
        undef,
        'main::capture_eval_frames()',
        undef,
        undef,
    ],
    'caller keeps eval STRING text on its own frame and leaves eval BLOCK text undefined',
);
is($eval_string_caller_name, '(eval)',
    'caller names an eval STRING frame as (eval)');
is_deeply(
    \@eval_block_frames,
    [
        ['main::capture_eval_block_frames', undef],
        ['(eval)', undef],
        ['main::eval_block_subroutine', undef],
        ['(eval)', 'main::eval_block_subroutine()'],
        ['main::call_eval_block_through_string', undef],
    ],
    'eval BLOCK caller text stays undefined inside an active eval STRING',
);

done_testing;
