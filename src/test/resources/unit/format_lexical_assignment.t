use strict;
use warnings;
use Test::More;

my $format_value = 10;
format FORMAT_LEXICAL_ASSIGNMENT =
@###
$format_value
.
$format_value = 42;

my $path = 'format_lexical_assignment.tmp';
open my $fh, '>', $path or die "open $path: $!";
my $old_fh = select((select($fh), $~ = 'FORMAT_LEXICAL_ASSIGNMENT')[0]);
write $fh;
select $old_fh;
close $fh or die "close $path: $!";

open my $read_fh, '<', $path or die "read $path: $!";
my $rendered = do { local $/; <$read_fh> };
close $read_fh or die "close $path after read: $!";
unlink $path or die "unlink $path: $!";

is($rendered, "  42\n",
    'interpreter format reads the current lexical cell after assignment');

done_testing;
