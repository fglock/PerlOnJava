use strict;
use warnings;
use Test::More;

our $ellipsis_value = 'of human events';
format ELLIPSIS_FORMAT =
^<<<<<<...
$ellipsis_value
.

sub render_ellipsis_format {
    my $path = 'format_continuation_ellipsis.tmp';
    open my $fh, '>', $path or die "open $path: $!";
    select((select($fh), $~ = 'ELLIPSIS_FORMAT')[0]);
    write $fh;
    close $fh or die "close $path: $!";
    open my $read_fh, '<', $path or die "read $path: $!";
    my $rendered = do { local $/; <$read_fh> };
    close $read_fh or die "close $path after read: $!";
    unlink $path or die "unlink $path: $!";
    return $rendered;
}

is(render_ellipsis_format(), "of huma...\n",
    'a continuation picture with ellipsis cuts at the picture width');
is($ellipsis_value, 'events',
    'an ellipsis continuation picture consumes the truncated source prefix');

$ellipsis_value = 'fit          ';
is(render_ellipsis_format(), "fit\n",
    'write suppresses an ellipsis when only trailing whitespace remains');
is($ellipsis_value, '',
    'a continuation picture consumes trailing whitespace without rendering ellipsis');

our $newline_value = "time\n";
format NEWLINE_TEXT_FORMAT =
@>>>>
$newline_value
.

my $newline_path = 'format_newline_text.tmp';
open my $newline_fh, '>', $newline_path or die "open $newline_path: $!";
select((select($newline_fh), $~ = 'NEWLINE_TEXT_FORMAT')[0]);
write $newline_fh;
close $newline_fh or die "close $newline_path: $!";
open my $newline_read_fh, '<', $newline_path or die "read $newline_path: $!";
my $newline_rendered = do { local $/; <$newline_read_fh> };
close $newline_read_fh or die "close $newline_path after read: $!";
unlink $newline_path or die "unlink $newline_path: $!";

is($newline_rendered, " time\n",
    'ordinary text pictures consume a terminal input newline');

our $block_good = 'good';
format BLOCK_ARGUMENT_FORMAT =
@<<<< @<<<< @<<<< @<<<<
{
    'i' . 's', "time\n", $block_good, 'to'
}
.

my $block_path = 'format_block_argument.tmp';
open my $block_fh, '>', $block_path or die "open $block_path: $!";
select((select($block_fh), $~ = 'BLOCK_ARGUMENT_FORMAT')[0]);
write $block_fh;
close $block_fh or die "close $block_path: $!";
open my $block_read_fh, '<', $block_path or die "read $block_path: $!";
my $block_rendered = do { local $/; <$block_read_fh> };
close $block_read_fh or die "close $block_path after read: $!";
unlink $block_path or die "unlink $block_path: $!";

is($block_rendered, "is    time  good  to\n",
    'a braced multiline format argument supplies its block list values');

done_testing;
