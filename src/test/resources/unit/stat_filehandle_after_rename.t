use strict;
use warnings;

use File::Temp qw(tempdir);
use Test::More;

my $dir = tempdir(CLEANUP => 1);
my $current = "$dir/current.log";
my $rotated = "$dir/current.log.1";

open my $cached, '>>', $current or die "open cached: $!";
print {$cached} 'cached-1';

rename $current, $rotated or die "rename: $!";
open my $replacement, '>>', $current or die "open replacement: $!";
print {$replacement} 'replacement';

my @cached_stat = stat $cached;
my @path_stat = stat $current;
isnt($cached_stat[1], $path_stat[1], 'stat on a renamed handle retains its original inode');

print {$cached} '-cached-2';
close $cached;
close $replacement;

open my $rotated_fh, '<', $rotated or die "read rotated: $!";
my $rotated_text = do { local $/; <$rotated_fh> };
close $rotated_fh;
open my $current_fh, '<', $current or die "read current: $!";
my $current_text = do { local $/; <$current_fh> };
close $current_fh;

is($rotated_text, 'cached-1-cached-2', 'cached handle still addresses the renamed file');
is($current_text, 'replacement', 'replacement path has independent contents');

done_testing;
