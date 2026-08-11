use strict;
use warnings;
use Cwd qw(getcwd);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use Test::More;

my $root = tempdir(CLEANUP => 1);
make_path(File::Spec->catdir($root, 'lib', 'Example'));
make_path(File::Spec->catdir($root, 't'));

open my $makefile_pl, '>', File::Spec->catfile($root, 'Makefile.PL')
    or die "cannot write Makefile.PL: $!";
print {$makefile_pl} "# generated test distribution\n";
close $makefile_pl;

my $production = File::Spec->catfile($root, 'lib', 'Example', 'App.pm');
open my $prod, '>', $production or die "cannot write $production: $!";
print {$prod} "package Example::App; sub origin { 'production' } 1;\n";
close $prod;

my $test_helper = File::Spec->catfile($root, 't', 'private.pl');
open my $test, '>', $test_helper or die "cannot write $test_helper: $!";
print {$test} "package Example::App; use Test::More; sub origin { 'test' } 1;\n";
close $test;

my $old = getcwd();
chdir $root or die "cannot chdir to $root: $!";
require ExtUtils::MakeMaker;
ExtUtils::MakeMaker::WriteMakefile(NAME => 'Example::App', VERSION => '1.00');
ok(system($ENV{MAKE} || 'make') == 0, 'MakeMaker stages the distribution');

my $staged = File::Spec->catfile('blib', 'lib', 'Example', 'App.pm');
open my $fh, '<', $staged or die "cannot read $staged: $!";
my $content = do { local $/; <$fh> };
close $fh;
like($content, qr/origin \{ 'production' \}/,
     'a test program declaring the production package cannot overwrite blib');
unlike($content, qr/use Test::More/,
       'test-only dependencies are not promoted into the installed module');

chdir $old or die "cannot restore cwd: $!";
done_testing;
