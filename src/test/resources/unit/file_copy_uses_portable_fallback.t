use strict;
use warnings;

use File::Copy qw(copy);
use File::Temp qw(tempdir);
use Test::More tests => 2;

my $dir = tempdir(CLEANUP => 1);
my $source = "$dir/source.txt";
my $destination = "$dir/destination.txt";

open my $input, '>', $source or die "open $source: $!";
print {$input} "portable copy fallback\n";
close $input or die "close $source: $!";

ok(copy($source, $destination),
    'File::Copy copies files when the native platform helper is unavailable');
open my $output, '<', $destination or die "open $destination: $!";
is(do { local $/; <$output> }, "portable copy fallback\n",
    'the copied file retains its contents');
close $output or die "close $destination: $!";
