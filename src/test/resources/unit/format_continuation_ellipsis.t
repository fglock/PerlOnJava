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

my %lexical_format_hash = (value => 'seen');
format LEXICAL_HASH_FORMAT =
@<<<<
"$lexical_format_hash{value}"
.

my $hash_path = 'format_lexical_hash.tmp';
open my $hash_fh, '>', $hash_path or die "open $hash_path: $!";
select((select($hash_fh), $~ = 'LEXICAL_HASH_FORMAT')[0]);
write $hash_fh;
close $hash_fh or die "close $hash_path: $!";
open my $hash_read_fh, '<', $hash_path or die "read $hash_path: $!";
my $hash_rendered = do { local $/; <$hash_read_fh> };
close $hash_read_fh or die "close $hash_path after read: $!";
unlink $hash_path or die "unlink $hash_path: $!";

is($hash_rendered, "seen\n",
    'a format argument expression sees its declaration-scope lexical hash');

format REPEATING_FORMAT =
@######## ~~
10
.

my $repeat_path = 'format_repeating_picture.tmp';
open(REPEATING_FORMAT, '>', $repeat_path) or die "open $repeat_path: $!";
my $repeat_result = eval { write(REPEATING_FORMAT) };
like($@, qr/Repeated format line will never terminate/,
    'write reports a non-terminating repeat picture through eval $@');
ok(!defined($repeat_result),
    'a failed format write returns undef from eval');
close REPEATING_FORMAT or die "close $repeat_path: $!";
unlink $repeat_path or die "unlink $repeat_path: $!";

format REPEAT_FOLLOWUP =
followup
.

my $followup_path = 'format_repeating_followup.tmp';
open(REPEAT_FOLLOWUP, '>', $followup_path) or die "open $followup_path: $!";
ok(write(REPEAT_FOLLOWUP),
    'a later format write succeeds after the eval-caught format error');
close REPEAT_FOLLOWUP or die "close $followup_path: $!";
unlink $followup_path or die "unlink $followup_path: $!";

our $trailing_decimal_value = 9999.6;
format TRAILING_DECIMAL =
@###.
$trailing_decimal_value
.

my $trailing_decimal_path = 'format_trailing_decimal.tmp';
open(TRAILING_DECIMAL, '>', $trailing_decimal_path)
    or die "open $trailing_decimal_path: $!";
ok(write(TRAILING_DECIMAL), 'a trailing-decimal numeric format writes');
close TRAILING_DECIMAL or die "close $trailing_decimal_path: $!";
open my $trailing_decimal_read, '<', $trailing_decimal_path
    or die "read $trailing_decimal_path: $!";
my $trailing_decimal_output = do { local $/; <$trailing_decimal_read> };
close $trailing_decimal_read or die "close read $trailing_decimal_path: $!";
unlink $trailing_decimal_path or die "unlink $trailing_decimal_path: $!";
is($trailing_decimal_output, "#####\n",
    'a trailing-decimal numeric picture overflows across its full width');

our %repeat_each_hash = (key => 'value');
format REPEAT_EACH =
@>>>> @<<<< ~~
each %repeat_each_hash
.

my $repeat_each_path = 'format_repeat_each.tmp';
open(REPEAT_EACH, '>', $repeat_each_path) or die "open $repeat_each_path: $!";
ok(write(REPEAT_EACH), 'a repeated each format writes');
close REPEAT_EACH or die "close $repeat_each_path: $!";
open my $repeat_each_read, '<', $repeat_each_path
    or die "read $repeat_each_path: $!";
my $repeat_each_output = do { local $/; <$repeat_each_read> };
close $repeat_each_read or die "close read $repeat_each_path: $!";
unlink $repeat_each_path or die "unlink $repeat_each_path: $!";
like($repeat_each_output, qr/key\s+value/,
    'a repeated each format consumes a hash pair before terminating');

my $format_reference = [];
format REFERENCE_FILL =
>^*<
$format_reference
.

my $reference_path = 'format_reference_fill.tmp';
open(REFERENCE_FILL, '>', $reference_path) or die "open $reference_path: $!";
ok(write(REFERENCE_FILL), 'a reference fill format writes');
close REFERENCE_FILL or die "close $reference_path: $!";
open my $reference_read, '<', $reference_path or die "read $reference_path: $!";
my $reference_output = do { local $/; <$reference_read> };
close $reference_read or die "close read $reference_path: $!";
unlink $reference_path or die "unlink $reference_path: $!";
like($reference_output, qr/^>ARRAY\(0x[0-9a-f]+\)<\n$/,
    'a fill-mode format preserves a reference stringification');

done_testing;
