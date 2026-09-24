use strict;
use warnings;
use File::Temp qw(tempdir);
use Test::More;

{
    package FileTestStringifyPath;
    use overload '""' => sub { $_[0]->{path} }, fallback => 1;
}

my $dir = tempdir(CLEANUP => 1);
my $path = bless {path => $dir}, 'FileTestStringifyPath';

ok(-d $path, 'file tests stringify an overloaded path object');

my $temporary_directory = File::Temp->newdir('filetestXXXXX');
ok(-d $temporary_directory, 'file tests stringify File::Temp directory objects');

done_testing;
