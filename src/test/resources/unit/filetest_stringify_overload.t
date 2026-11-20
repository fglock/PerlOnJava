use strict;
use warnings;
use File::Temp qw(tempdir tempfile);
use Test::More;

{
    package FileTestStringifyPath;
    use overload '""' => sub { $_[0]->{path} }, fallback => 1;
}

{
    package FileTestStringifyHandle;
    our $stringify_calls = 0;
    use overload '""' => sub { $stringify_calls++; 'not-a-path' }, fallback => 1;
}

my $dir = tempdir(CLEANUP => 1);
my $path = bless {path => $dir}, 'FileTestStringifyPath';

ok(-d $path, 'file tests stringify an overloaded path object');

my $temporary_directory = File::Temp->newdir('filetestXXXXX');
ok(-d $temporary_directory, 'file tests stringify File::Temp directory objects');

my ($filehandle, $filename) = tempfile();
print {$filehandle} "filetest\n";
close $filehandle or die "close temporary file: $!";

open my $glob, '<', $filename or die "open temporary file: $!";
my $io = *{$glob}{IO};
stat $io;
ok(-T _, 'text file test uses the stat result for an IO reference');

bless $glob, 'FileTestStringifyHandle';
$FileTestStringifyHandle::stringify_calls = 0;
ok(-r $glob, 'readability test accepts a blessed glob filehandle');
is($FileTestStringifyHandle::stringify_calls, 0,
    'readability test does not stringify a blessed glob filehandle');

bless $io, 'FileTestStringifyHandle';
$FileTestStringifyHandle::stringify_calls = 0;
ok(-r $io, 'readability test accepts a blessed IO reference');
is($FileTestStringifyHandle::stringify_calls, 0,
    'readability test does not stringify a blessed IO reference');
stat $glob;
close $glob or die "close filehandle: $!";
{
    local $SIG{__WARN__} = sub { };
    -T _;
}
isnt(stat _, 1, 'closing a glob resets the cached stat target');

done_testing;
