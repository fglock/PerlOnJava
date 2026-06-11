use strict;
use warnings;
use Test::More tests => 3;
use File::Spec;
use File::Temp qw(tempdir);

my $base = tempdir(CLEANUP => 1);
my $dir = File::Spec->catdir($base, 'abc-testcmd-1');
mkdir $dir or die "mkdir $dir: $!";

my $file = File::Spec->catfile($dir, 'file1');
open my $fh, '>', $file or die "open $file: $!";
print {$fh} "hello\n";
close $fh or die "close $file: $!";

my $pattern = File::Spec->catfile($base, '*testcmd*', 'file1');
my $match = glob($pattern);

ok(defined $match, 'absolute recursive glob found a match');
ok(File::Spec->file_name_is_absolute($match), 'absolute recursive glob returns an absolute path');
is($match, $file, 'absolute recursive glob returns full path');
