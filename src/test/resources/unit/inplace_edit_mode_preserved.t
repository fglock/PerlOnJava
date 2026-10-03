use strict;
use warnings;
use File::Temp qw(tempdir);
use Test::More;

my $dir = tempdir(CLEANUP => 1);
my $file = "$dir/input.txt";
open my $fh, '>', $file or die "open: $!";
print {$fh} "original\n";
close $fh or die "close: $!";

chmod 04600, $file or plan skip_all => 'cannot set setuid mode on this platform';
my $before = (stat($file))[2] & 07777;
plan skip_all => 'filesystem does not preserve setuid mode' unless $before & 04000;

{
    local @ARGV = ($file);
    local $^I = '';
    while (my $line = <>) {
        print $line;
    }
}

my $after = (stat($file))[2] & 07777;
is($after, $before, 'in-place editing preserves the source permission mode');
done_testing();
