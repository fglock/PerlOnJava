use strict;
use warnings;
use Test::More;
use Config ();
use Cwd qw(getcwd);
use File::Temp qw(tempdir);

my $orig_dir = getcwd();
my $tmpdir = tempdir(CLEANUP => 1);

END {
    chdir $orig_dir if defined $orig_dir;
}

chdir $tmpdir or die "chdir $tmpdir: $!";

open my $makefile_pl, '>', 'Makefile.PL'
    or die "create Makefile.PL marker: $!";
print {$makefile_pl} "# generated test fixture\n";
close $makefile_pl or die "close Makefile.PL marker: $!";

open my $pl, '>', 'ReadKey.pm.PL'
    or die "create generated pm template: $!";
print {$pl} "open my \$input, '<', 'blib/lib/Term/Source.pm' or die \$!; local \$/; my \$source = <\$input>; close \$input; open my \$out, '>', \$ARGV[0] or die \$!; print {\$out} \$source; close \$out;\n";
close $pl or die "close generated pm template: $!";

mkdir 'lib' or die "mkdir lib: $!";
mkdir 'lib/Term' or die "mkdir lib/Term: $!";
open my $source, '>', 'lib/Term/Source.pm' or die "create source module: $!";
print {$source} "package Term::ReadKey; 1;\n";
close $source or die "close source module: $!";

mkdir 'lib/Parse' or die "mkdir lib/Parse: $!";
mkdir 'lib/Parse/Yapp' or die "mkdir lib/Parse/Yapp: $!";
open my $parent_module, '>', 'lib/Parse/Yapp/KeyValue.pm'
    or die "create staged parent module: $!";
print {$parent_module} "package Parse::Yapp::KeyValue; 1;\n";
close $parent_module or die "close staged parent module: $!";

open my $parser, '>', 'MakeParser.PL'
    or die "create nested-output generator: $!";
print {$parser} <<'PARSER';
my $path = 'blib/lib/Parse/Yapp/KeyValue';
my $file = "$path/Parser.pm";
mkdir $path or die "could not create directory $path: $!";
open my $out, '>', $file or die "unable to open $file: $!";
print {$out} "package Parse::Yapp::KeyValue::Parser; 1;\n";
close $out or die $!;
PARSER
close $parser or die "close nested-output generator: $!";

use ExtUtils::MakeMaker;

WriteMakefile(
    NAME     => 'Term::ReadKey',
    VERSION  => '0.001',
    PL_FILES => {
        'ReadKey.pm.PL' => '$(INST_LIB)/Term/ReadKey.pm',
        'MakeParser.PL' => '$(INST_LIB)/Parse/Yapp/KeyValue/Parser.pm',
    },
);

open my $mf, '<', 'Makefile' or die "open generated Makefile: $!";
my $makefile = do { local $/; <$mf> };
close $mf or die "close generated Makefile: $!";

like(
    $makefile,
    qr/^all\b.*:/m,
    'Makefile emits an all target',
);
like(
    $makefile,
    qr/^pm_to_blib\b.*:/m,
    'Makefile emits a valid pm_to_blib target',
);
like(
    $makefile,
    qr/ReadKey\.pm\.PL/,
    'PL_FILES command is still emitted',
);

my $make = $Config::Config{make} || 'make';
is(
    system($make, 'all'),
    0,
    'PL_FILES can generate a module into a nested blib directory',
);
ok(
    -f 'blib/lib/Term/ReadKey.pm',
    'PL_FILES creates its nested module target',
);
ok(-f 'blib/lib/Parse/Yapp/KeyValue/Parser.pm',
    'PL_FILES prepares nested parents before a generator creates its directory');
ok(-f 'pm_to_blib', 'pm_to_blib target creates its build marker');

subtest 'nested configuration stages generated output, not its Makefile.PL' => sub {
    plan skip_all => 'exercises PerlOnJava synthetic MakeMaker'
        unless $Config::Config{archname} =~ /^java-/;

    my $nested = 'Lite/Util';
    mkdir 'Lite' or die "mkdir Lite: $!";
    mkdir $nested or die "mkdir $nested: $!";

    open my $child, '>', "$nested/Makefile.PL"
        or die "create nested Makefile.PL: $!";
    print {$child} <<'CHILD';
use strict;
use warnings;
use ExtUtils::MakeMaker;
die "-noxs was not propagated\n" unless grep { $_ eq '-noxs' } @ARGV;
open my $out, '>', 'Util_IS.pm' or die $!;
print {$out} "package NetAddr::IP::Util_IS; sub pure { 1 } 1;\n";
close $out or die $!;
WriteMakefile(NAME => 'NetAddr::IP::Util', VERSION => '0.001');
CHILD
    close $child or die "close nested Makefile.PL: $!";

    local @ARGV = ('-noxs');
    WriteMakefile(NAME => 'NetAddr::IP', VERSION => '0.001', DIR => ['Lite/Util']);

    open my $parent_mf, '<', 'Makefile' or die "open parent Makefile: $!";
    my $parent_makefile = do { local $/; <$parent_mf> };
    close $parent_mf or die "close parent Makefile: $!";

    like($parent_makefile, qr{Lite/Util/Util_IS\.pm},
        'parent stages the nested generated module');
    unlike($parent_makefile, qr{Lite/Util/Makefile\.PL},
        'parent never stages the nested generator source as a module');

    mkdir 'Broken' or die "mkdir Broken: $!";
    open my $broken, '>', 'Broken/Makefile.PL'
        or die "create failing nested Makefile.PL: $!";
    print {$broken} "die qq{nested configure failure\\n};\n";
    close $broken or die "close failing nested Makefile.PL: $!";

    my $error;
    {
        local @ARGV;
        eval { WriteMakefile(NAME => 'Broken::Parent', VERSION => '0.001', DIR => ['Broken']); 1 }
            or $error = $@;
    }
    like($error, qr/subdir Broken configure failed/,
        'a failed nested configuration aborts the parent configure phase');
};

done_testing();
