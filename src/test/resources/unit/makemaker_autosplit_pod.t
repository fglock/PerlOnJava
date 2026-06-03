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

open my $pm, '>', 'lib/Local/Autosplit.pm'
    or die "create test module: $!";
print {$pm} "package Local::Autosplit;\n1;\n";
close $pm or die "close test module: $!";

open my $pod, '>', 'lib/Local/Autosplit.pod'
    or die "create test pod: $!";
print {$pod} "=head1 NAME\n\nLocal::Autosplit\n\n=cut\n";
close $pod or die "close test pod: $!";

use ExtUtils::MakeMaker;

WriteMakefile(
    NAME    => 'Local::Autosplit',
    VERSION => '0.001',
    PM      => {
        'lib/Local/Autosplit.pm'  => '$(INST_LIB)/Local/Autosplit.pm',
        'lib/Local/Autosplit.pod' => '$(INST_LIB)/Local/Autosplit.pod',
    },
);

open my $mf, '<', 'Makefile' or die "open generated Makefile: $!";
my $makefile = do { local $/; <$mf> };
close $mf or die "close generated Makefile: $!";

like(
    $makefile,
    qr/^pm_to_blib\b.*:/m,
    'Makefile emits a valid pm_to_blib target',
);
unlike(
    $makefile,
    qr/autosplit\(\$\$ARGV\[0\], \$\$ARGV\[1\], 0, 1, 1\)' '\$\(INST_LIB\)\/Local\/Autosplit\.pod'/,
    '.pod files are not autosplit',
);

done_testing();
