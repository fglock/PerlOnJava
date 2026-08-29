use strict;
use warnings;
use Test::More tests => 3;
use File::Temp qw(tempdir);
use File::Spec;

require './dev/import-perl5/sync.pl';

my $directory = tempdir(CLEANUP => 1);
my $source = File::Spec->catfile($directory, 'source.pm');
my $destination = File::Spec->catfile($directory, 'staged.pm');

open my $source_fh, '>', $source or die "Cannot create $source: $!";
print {$source_fh} "package Imported; 1;\n";
close $source_fh or die "Cannot close $source: $!";
chmod 0644, $source or die "Cannot chmod $source: $!";

open my $destination_fh, '>', $destination
    or die "Cannot create $destination: $!";
close $destination_fh or die "Cannot close $destination: $!";
chmod 0600, $destination or die "Cannot chmod $destination: $!";

ok(copy_file_preserving_mode($source, $destination),
   'staging copy succeeds');
is((stat($destination))[2] & 07777, 0644,
   'staging copy preserves the source mode');
open my $copied_fh, '<', $destination or die "Cannot read $destination: $!";
is(do { local $/; <$copied_fh> }, "package Imported; 1;\n",
   'staging copy preserves the source contents');
