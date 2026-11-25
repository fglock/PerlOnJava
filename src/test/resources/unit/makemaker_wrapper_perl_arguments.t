use strict;
use warnings;
use Test::More;
use Config;
use Cwd qw(getcwd);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);

if ($^O eq 'MSWin32') {
    plan skip_all => 'shell-wrapper execution is Unix-only';
}

my $original_dir = getcwd();
my $temporary_dir = tempdir(CLEANUP => 1);
END { chdir $original_dir if defined $original_dir }
chdir $temporary_dir or die "chdir $temporary_dir: $!";
make_path('script', 'inc');
make_path('lib/Local');
open my $module, '>', 'lib/Local/WrapperArguments.pm'
    or die "create lib/Local/WrapperArguments.pm: $!";
print {$module} "package Local::WrapperArguments;\nour \$VERSION = '0.001';\n1;\n";
close $module or die "close lib/Local/WrapperArguments.pm: $!";
open my $script, '>', 'script/probe' or die "create script/probe: $!";
print {$script} "#!perl -w\nprint qq(wrapper-ok\\n);\n";
close $script or die "close script/probe: $!";

require ExtUtils::MakeMaker;
plan skip_all => 'bundled MakeMaker is not loaded'
    unless $ExtUtils::MakeMaker::VERSION eq '7.78';
open my $makefile_pl, '>', 'Makefile.PL' or die "create Makefile.PL: $!";
close $makefile_pl or die "close Makefile.PL: $!";
ExtUtils::MakeMaker::WriteMakefile(
    NAME      => 'Local::WrapperArguments',
    VERSION   => '0.001',
    EXE_FILES => ['script/probe'],
    macro     => { PERL => "$^X \"-Iinc\"" },
);

my $make = $Config::Config{make} || 'make';
is(system($make, 'pure_all'), 0, 'script staging succeeds with Perl arguments');
my $wrapper = File::Spec->catfile('blib', 'script', 'probe');
ok(-x $wrapper, 'staged script wrapper is executable');
my $output = qx{"$wrapper"};
is($?, 0, 'wrapper executes its configured Perl command');
is($output, "wrapper-ok\n", 'wrapper passes the staged script to Perl');
done_testing();
