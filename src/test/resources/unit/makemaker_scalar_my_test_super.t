use strict;
use warnings;
use Test::More;
use Config;
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
        my $inherited = shift->SUPER::test(@_);
        $inherited =~ s/\$\(TEST_FILES\)/-Iscalar-context \$(TEST_FILES)/;
        $inherited;
    }
}

use ExtUtils::MakeMaker;
WriteMakefile(NAME => 'Local::ScalarSuperProbe', VERSION => '0.001');

open my $makefile_fh, '<', 'Makefile' or die "open Makefile: $!";
my $makefile = do { local $/; <$makefile_fh> };
close $makefile_fh or die "close Makefile: $!";

like($makefile, qr/-Iscalar-context \$\(TEST_FILES\)/,
    'scalar-context SUPER::test retains and allows edits to the inherited rule');
unlike($makefile, qr/^\d+\s*$/m,
    'scalar-context SUPER::test does not become a bare list count');

my $make = $Config::Config{make} || 'make';
is(system($make, '-n', 'test'), 0,
    'scalar-context MY::test output parses as a valid make target');

done_testing();
