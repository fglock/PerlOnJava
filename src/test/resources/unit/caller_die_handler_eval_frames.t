use strict;
use warnings;
use Test::More;

my (@eval_string_frame, @eval_block_frame);
{
    local $SIG{__DIE__} = sub {
        @eval_string_frame = caller(1);
    };
    my $eval_string_line = __LINE__ + 1;
    &{ sub { eval q{die}; } }();
    is $eval_string_frame[3], '(eval)',
        'eval STRING is caller(1) in its die handler';
    is $eval_string_frame[1], __FILE__,
        'eval STRING caller location uses the host source file';
    is $eval_string_frame[2], $eval_string_line,
        'eval STRING caller location uses the eval call site';
}

my $eval_block_line = __LINE__ + 1;
sub die_in_eval_block { eval { die }; }
{
    local $SIG{__DIE__} = sub {
        @eval_block_frame = caller(1);
    };
    die_in_eval_block();
    is $eval_block_frame[3], '(eval)',
        'eval BLOCK is caller(1) in its die handler';
    is $eval_block_frame[1], __FILE__,
        'eval BLOCK caller location uses the host source file';
    is $eval_block_frame[2], $eval_block_line,
        'eval BLOCK caller location uses the eval block source line';
}

done_testing();
