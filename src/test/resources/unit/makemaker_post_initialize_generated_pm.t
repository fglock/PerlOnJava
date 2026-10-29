use strict;
use warnings;
use Test::More tests => 4;
use File::Temp qw(tempdir);
use Cwd qw(getcwd);

my $dir = tempdir(CLEANUP => 1);
my $cwd = getcwd();
chdir $dir or die "chdir $dir: $!";

mkdir 'lib' or die "mkdir lib: $!";
mkdir 'lib/Fixture' or die "mkdir lib/Fixture: $!";
open my $module, '>', 'lib/Fixture/Module.pm' or die $!;
print {$module} "package Fixture::Module; 1;\n";
close $module;
open my $makefile_pl, '>', 'Makefile.PL' or die $!;
print {$makefile_pl} "# generated test fixture\n";
close $makefile_pl;

{
    package MY;
    sub post_initialize {
        my ($self) = @_;
        $self->{clean}{FILES} = 'lib/Fixture/Generated.store';
        $self->{PM}{'lib/Fixture/Generated.store'} = 'blib/lib/Fixture/Generated.store';
        return '';
    }
    sub postamble {
        return "\nlib/Fixture/Generated.store :\n\tprintf generated > lib/Fixture/Generated.store\n";
    }
}

use ExtUtils::MakeMaker;
WriteMakefile(NAME => 'Fixture::Module', VERSION => '0.01');

ok(!-e 'lib/Fixture/Generated.store', 'configure does not fabricate generated data');
ok(-f 'Makefile', 'Makefile was generated');
is(system($ENV{MAKE} || 'make', 'all'), 0, 'make runs the custom generated-file rule');
ok(-f 'blib/lib/Fixture/Generated.store', 'pm_to_blib stages the generated file');

chdir $cwd or die "chdir $cwd: $!";
