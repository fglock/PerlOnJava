use strict;
use warnings;
use Test::More;
use Cwd qw(getcwd);
use File::Path qw(make_path);
use File::Temp qw(tempdir);

my $orig_dir = getcwd();
my $tmpdir = tempdir(CLEANUP => 1);

END {
    chdir $orig_dir if defined $orig_dir;
}

chdir $tmpdir or die "chdir $tmpdir: $!";
make_path('lib/Local') or die "make_path lib/Local: $!";

open my $pm, '>', 'lib/Local/BlibArch.pm'
    or die "create test module: $!";
print {$pm} "package Local::BlibArch;\n1;\n";
close $pm or die "close test module: $!";

use ExtUtils::MakeMaker;

WriteMakefile(
    NAME    => 'Local::BlibArch',
    VERSION => '0.001',
    PM      => {
        'lib/Local/BlibArch.pm' => '$(INST_LIB)/Local/BlibArch.pm',
    },
);

open my $mf, '<', 'Makefile' or die "open generated Makefile: $!";
my $makefile = do { local $/; <$mf> };
close $mf or die "close generated Makefile: $!";

like($makefile, qr/^INST_LIB = blib\/lib$/m, 'INST_LIB defaults to blib/lib');
like($makefile, qr/^INST_ARCHLIB = blib\/arch$/m, 'INST_ARCHLIB defaults to blib/arch');
like(
    $makefile,
    qr/^\t\@mkdir -p \$\(INST_ARCHLIB\)$/m,
    'pm_to_blib creates INST_ARCHLIB for -Mblib',
);

done_testing();
