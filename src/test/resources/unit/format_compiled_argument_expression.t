use strict;
use warnings;
use Test::More;

my $format_value = 10;
my $format_calls = 0;
sub next_format_value { return ++$format_calls; }

format FORMAT_COMPILED_ARGUMENT_EXPRESSION =
@### @###
($format_value + 0), next_format_value()
.
$format_value = 42;

my $path = 'format_compiled_argument_expression.tmp';
open my $fh, '>', $path or die "open $path: $!";
my $old_fh = select((select($fh), $~ = 'FORMAT_COMPILED_ARGUMENT_EXPRESSION')[0]);
write $fh;
select $old_fh;
close $fh or die "close $path: $!";

open my $read_fh, '<', $path or die "read $path: $!";
my $rendered = do { local $/; <$read_fh> };
close $read_fh or die "close $path after read: $!";
unlink $path or die "unlink $path: $!";

is($rendered, "  42    1\n", 'compiled format expressions see current values and render in order');
is($format_calls, 1, 'each format argument expression runs exactly once');

{
    package FormatExpressionPackage;
    our $package_value = 40;
    format FORMAT_COMPILED_PACKAGE_EXPRESSION =
@###
$package_value + 2
.
}
my $package_path = 'format_compiled_package_expression.tmp';
open my $package_fh, '>', $package_path or die "open $package_path: $!";
$old_fh = select((select($package_fh), $~ = 'FormatExpressionPackage::FORMAT_COMPILED_PACKAGE_EXPRESSION')[0]);
write $package_fh;
select $old_fh;
close $package_fh or die "close $package_path: $!";
open my $package_read_fh, '<', $package_path or die "read $package_path: $!";
my $package_rendered = do { local $/; <$package_read_fh> };
close $package_read_fh or die "close $package_path after read: $!";
unlink $package_path or die "unlink $package_path: $!";
is($package_rendered, "  42\n", 'format expressions resolve globals in the declaration package');

done_testing;
