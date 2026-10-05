use strict;
use warnings;
use Test::More;
use Cwd qw(getcwd);
use File::Temp qw(tempdir);

my $orig_dir = getcwd();
my $tmpdir = tempdir(CLEANUP => 1);
END { chdir $orig_dir if defined $orig_dir }
chdir $tmpdir or die "chdir $tmpdir: $!";

open my $marker, '>', 'Makefile.PL' or die "create Makefile.PL: $!";
close $marker or die "close Makefile.PL: $!";

{
    package MY;
    sub test {
        return <<'MAKE_RULE';
test :: pure_all
	@echo custom test command -Igen-perl -Igen-perl2
MAKE_RULE
    }
}

use ExtUtils::MakeMaker;
WriteMakefile(NAME => 'Local::ThriftProbe', VERSION => '0.001');

open my $makefile_fh, '<', 'Makefile' or die "open Makefile: $!";
my $makefile = do { local $/; <$makefile_fh> };
close $makefile_fh or die "close Makefile: $!";

like($makefile, qr/custom test command -Igen-perl -Igen-perl2/,
    'MY::test custom include paths appear in the generated test target');
unlike($makefile, qr/test_harness\(0, '\$\(INST_LIB\)'/,
    'custom MY::test replaces the default test target');
like($makefile, qr/^INSTALLARCHLIB = .+$/m,
    'Makefile defines INSTALLARCHLIB for Alien::Build::MM');
like($makefile, qr/^INSTALLSITEARCH = .+$/m,
    'Makefile defines INSTALLSITEARCH for Alien::Build::MM');
like($makefile, qr/^INSTALLVENDORARCH = .+$/m,
    'Makefile defines INSTALLVENDORARCH for Alien::Build::MM');

done_testing();
