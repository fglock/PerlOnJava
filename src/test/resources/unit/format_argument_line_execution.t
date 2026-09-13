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

done_testing;
