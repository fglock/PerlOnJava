use strict;
use warnings;
use Test::More tests => 5;
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

my $generator = File::Spec->catfile($directory, 'fake-mktables.pl');
my $result = File::Spec->catfile($directory, 'debugging-result.txt');
open my $generator_fh, '>', $generator
    or die "Cannot create $generator: $!";
print {$generator_fh} <<'FAKE_MKTABLES';
use strict;
use warnings;
use Config;
open my $result_fh, '>', $ARGV[0] or die "Cannot create result: $!";
print {$result_fh} Config::DEBUGGING, "\n";
close $result_fh or die "Cannot close result: $!";
FAKE_MKTABLES
close $generator_fh or die "Cannot close $generator: $!";

is(run_unicode_mktables($generator, $result), 0,
   'mktables runner supports host Config without DEBUGGING');
open my $result_fh, '<', $result or die "Cannot read $result: $!";
is(do { local $/; <$result_fh> }, "0\n",
   'mktables runner supplies the non-debug Config default');
