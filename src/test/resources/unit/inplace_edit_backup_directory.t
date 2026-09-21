use strict;
use warnings;
use File::Temp qw(tempdir);
use Test::More;

my $dir = tempdir(CLEANUP => 1);
my $file = "$dir/input.txt";
open my $fh, '>', $file or die "open: $!";
print {$fh} "original\n";
close $fh or die "close: $!";
mkdir "$file.bak" or die "mkdir: $!";

my $error;
{
    local @ARGV = ($file);
    local $^I = '.bak';
    eval {
        while (<>) {
            print;
        }
    };
    $error = $@;
}

like($error, qr/Can't rename .*input\.txt to .*input\.txt\.bak: Is a directory/, 'in-place backup directory reports rename failure');
ok(-f $file, 'source file remains after failed backup rename');
ok(-d "$file.bak", 'backup directory remains after failed backup rename');

done_testing;
