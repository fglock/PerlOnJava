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
make_path('lib/Math') or die "make_path lib/Math: $!";

open my $pm, '>', 'lib/Math/Complex.pm'
    or die "create test module: $!";
print {$pm} "package Math::Complex;\nour \$VERSION = '0.001';\n1;\n";
close $pm or die "close test module: $!";

use ExtUtils::MakeMaker;

WriteMakefile(
    NAME    => 'Math::Complex',
    VERSION => '0.001',
    PM      => {
        'lib/Math/Complex.pm' => '$(INST_LIB)/Math/Complex.pm',
    },
);

open my $mf, '<', 'Makefile' or die "open generated Makefile: $!";
my $makefile = do { local $/; <$mf> };
close $mf or die "close generated Makefile: $!";

like(
    $makefile,
    qr/^pm_to_blib::$/m,
    'fully bundled distributions still emit a valid pm_to_blib rule',
);
unlike(
    $makefile,
    qr/^pm_to_blib$/m,
    'fully bundled distributions do not emit a target without a colon',
);
like(
    $makefile,
    qr/^test::\n\t\@\S*jperl -e 'print "PerlOnJava: Math::Complex is bundled in the JAR;/m,
    'fully bundled distributions generate a no-op bundled test target',
);

done_testing();
