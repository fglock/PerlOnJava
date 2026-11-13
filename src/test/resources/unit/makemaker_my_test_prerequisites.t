use strict;
use warnings;
use Config;
use Test::More;
use Cwd qw(getcwd);
use File::Temp qw(tempdir);

my $orig_dir = getcwd();
my $tmpdir = tempdir(CLEANUP => 1);
END { chdir $orig_dir if defined $orig_dir }
chdir $tmpdir or die "chdir $tmpdir: $!";

open my $marker, '>', 'Makefile.PL' or die "create Makefile.PL: $!";
close $marker or die "close Makefile.PL: $!";
mkdir 't' or die "mkdir t: $!";

{
    package MY;
    sub test {
        my $make = shift->SUPER::test(@_);
        $make =~ s/(test ::)(.*)/$1 DATA$2/g;
        return $make;
    }

    sub postamble {
        return "DATA ::\n\t\@echo generated test data\n";
    }
}

use ExtUtils::MakeMaker;
WriteMakefile(NAME => 'Local::MakeMakerTestPrerequisites', VERSION => '0.001');

open my $makefile_fh, '<', 'Makefile' or die "open Makefile: $!";
my $makefile = do { local $/; <$makefile_fh> };
close $makefile_fh or die "close Makefile: $!";

like($makefile, qr/^test :: DATA\b[^\n]*$/m,
    'MY::test can add a generated-data prerequisite to the standard test target');
like($makefile, qr/^DATA ::$/m,
    'custom generated-data prerequisite is present in the postamble');

my $make = $Config{make} || 'make';
is(system($make, '-n', 'test'), 0,
    'custom test prerequisite produces a valid make target');

done_testing();
