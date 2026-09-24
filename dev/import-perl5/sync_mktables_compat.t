use strict;
use warnings;
use Test::More tests => 2;
use File::Spec;
use File::Temp qw(tempdir);

require './dev/import-perl5/sync.pl';

my $directory = tempdir(CLEANUP => 1);
my $generator = File::Spec->catfile($directory, 'mktables-fixture.pl');
my $marker = File::Spec->catfile($directory, 'ran');

open my $generator_fh, '>', $generator
    or die "Cannot create $generator: $!";
print {$generator_fh} <<'FIXTURE';
use strict;
use warnings;
use Config;
my $debugging_build = Config::DEBUGGING;
open my $marker_fh, '>', $ARGV[0] or die "Cannot create marker: $!";
print {$marker_fh} "ran\n";
close $marker_fh or die "Cannot close marker: $!";
FIXTURE
close $generator_fh or die "Cannot close $generator: $!";

is(run_unicode_mktables($generator, $marker), 0,
   'compatibility launcher runs a generator using Config::DEBUGGING');
open my $marker_fh, '<', $marker or die "Cannot read marker: $!";
is(do { local $/; <$marker_fh> }, "ran\n",
   'compatibility launcher executes the generator body');
