use strict;
use warnings;
use Test::More;
use Cwd qw(getcwd);
use File::Path qw(make_path);
use File::Temp qw(tempdir);

my $orig_dir = getcwd();
my $tmpdir = tempdir(CLEANUP => 1);
END { chdir $orig_dir if defined $orig_dir }

chdir $tmpdir or die "chdir $tmpdir: $!";
make_path('lib/Local') or die "make_path lib/Local: $!";

open my $pod_pm, '>', 'lib/Local/PodOnly.pm' or die "create POD-only module: $!";
print {$pod_pm} "package Local::PodOnly;\n1;\n__END__\n=head1 NAME\n\nLocal::PodOnly\n\n=cut\n";
close $pod_pm or die "close POD-only module: $!";

open my $loader_pm, '>', 'lib/Local/Loader.pm' or die "create AutoLoader module: $!";
print {$loader_pm} "package Local::Loader;\nuse AutoLoader;\n1;\n__END__\nsub deferred { 42 }\n";
close $loader_pm or die "close AutoLoader module: $!";

use ExtUtils::MakeMaker;
WriteMakefile(
    NAME => 'Local::AutosplitSelection', VERSION => '0.001',
    PM => {
        'lib/Local/PodOnly.pm' => '$(INST_LIB)/Local/PodOnly.pm',
        'lib/Local/Loader.pm'  => '$(INST_LIB)/Local/Loader.pm',
    },
);

open my $mf, '<', 'Makefile' or die "open generated Makefile: $!";
my $makefile = do { local $/; <$mf> };
close $mf or die "close generated Makefile: $!";

unlike($makefile, qr/autosplit\([^\n]+Local\/PodOnly\.pm/,
       'POD-only __END__ markers do not trigger AutoSplit');
ok($makefile =~ qr/autosplit\([^\n]+Local\/Loader\.pm/
       || $makefile =~ qr/pm_to_blib\(\{\@ARGV\}/,
   'modules using AutoLoader remain covered by the staging rule');

done_testing();
