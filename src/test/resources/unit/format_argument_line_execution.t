use strict;
use warnings;
use Test::More;

our $format_argument_line_counter = 0;

format FORMAT_ARGUMENT_LINE_EXECUTION =
@###|@###
++ $format_argument_line_counter, 2 + 3
.

my $path = 'format_argument_line_execution.tmp';
open my $fh, '>', $path or die "open $path: $!";
select((select($fh), $~ = 'FORMAT_ARGUMENT_LINE_EXECUTION')[0]);
write $fh;
close $fh or die "close $path: $!";
unlink $path or die "unlink $path: $!";

is($format_argument_line_counter, 1,
    'write executes expressions in a format argument line');

{
    local $^A = '';
    formline '@<<', 'foxiness';
    is($^A, 'fox', 'picture width includes the leading field sigil');
}

{
    local $^A = '';
    formline '@0##', 1;
    is($^A, '0001', 'zero picture glyph pads numeric fields');
}

{
    local $^A = '';
    formline '@', 'a';
    is($^A, 'a', 'single at-sign picture is a one-character field');
}

{
    local $~ = '';
    eval { write }; ## no critic (ErrorHandling::RequireCheckingReturnValueOfEval)
    like($@, qr/Undefined format ""/, 'write preserves an empty format name in its diagnostic');
}

{
    local $~ = 'NOSUCHFORMAT';
    eval { write }; ## no critic (ErrorHandling::RequireCheckingReturnValueOfEval)
    like($@, qr/Undefined format "NOSUCHFORMAT"/,
        'write reports an unqualified missing format name');
}

{
    local $~ = "\0foo";
    eval { write }; ## no critic (ErrorHandling::RequireCheckingReturnValueOfEval)
    like($@, qr/Undefined format "\0foo"/,
        'write preserves a NUL-prefixed missing format name');
}

done_testing;
