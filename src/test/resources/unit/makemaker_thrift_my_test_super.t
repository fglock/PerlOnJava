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
        my @result;
        for (@result = shift->SUPER::test(@_)) {
            s/\$\(TEST_FILES\)/-Igen-perl -Igen-perl2 \$(TEST_FILES)/ig;
        }
        @result;
    }
}

use ExtUtils::MakeMaker;
WriteMakefile(NAME => 'Local::ThriftSuperProbe', VERSION => '0.001');

open my $makefile_fh, '<', 'Makefile' or die "open Makefile: $!";
my $makefile = do { local $/; <$makefile_fh> };
close $makefile_fh or die "close Makefile: $!";

like($makefile, qr/-Igen-perl -Igen-perl2 \$\(TEST_FILES\)/,
    'list-context SUPER::test override retains Thrift generated include paths');
like($makefile, qr/^TEST_FILES = t\/\*\.t$/m,
    'MakeMaker exposes the selected test files through TEST_FILES');
unlike($makefile, qr/^1\s*$/m,
    'list return value is not reduced to a scalar count in the Makefile');

my $make = $Config::Config{make} || 'make';
is(system($make, '-n', 'test'), 0,
    'Thrift-style MY::test output parses as a valid make target');

done_testing();
