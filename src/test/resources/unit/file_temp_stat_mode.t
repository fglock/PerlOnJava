use strict;
use warnings;
use Test::More;
use File::Temp qw(tempfile);

my ($fh, $path) = tempfile('perlonjava-file-temp-XXXXXX', UNLINK => 0);
my @handle_stat = stat $fh;
my @path_stat = stat $path;

is($handle_stat[0], $path_stat[0], 'tempfile handle and path have the same device');
is($handle_stat[1], $path_stat[1], 'tempfile handle and path have the same inode');
is($handle_stat[2], $path_stat[2], 'tempfile handle and path have the same mode');
is($handle_stat[3], $path_stat[3], 'tempfile handle and path have the same link count');

close $fh;
unlink $path;

done_testing;
