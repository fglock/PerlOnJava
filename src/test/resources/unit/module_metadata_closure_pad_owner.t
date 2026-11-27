use strict;
use warnings;
use Test::More tests => 1;
use File::Temp qw(tempdir);
use Module::Metadata;

my $directory = tempdir(CLEANUP => 1);
my $file = "$directory/ArrayRef.pm";
open my $fh, '>', $file or die "open $file: $!";
print {$fh} "package Types::Standard::ArrayRef;\n1;\n";
close $fh or die "close $file: $!";

my $metadata = Module::Metadata->new_from_file($file);
is($metadata->name, 'Types::Standard::ArrayRef',
    'package inference preserves closure-pad ownership');
