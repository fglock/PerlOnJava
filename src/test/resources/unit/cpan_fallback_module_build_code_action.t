use strict;
use warnings;
use Cwd ();
use File::Path qw(make_path);
use File::Temp qw(tempdir);
use Test::More;

plan skip_all => 'PerlOnJava CPAN fallback hook is not installed'
    unless eval {
        require CPAN::Distribution;
        CPAN::Distribution->can('_perlonjava_run_build_code_action');
    };

{
    no warnings 'once';
    $CPAN::Frontend = bless {}, 'Local::QuietCPANFrontend';
}

my $original_dir = Cwd::getcwd();
my $build_dir = tempdir(CLEANUP => 1);
chdir $build_dir or die "cannot chdir to $build_dir: $!";

open my $build_pl, '>', 'Build.PL' or die "cannot write Build.PL: $!";
print {$build_pl} <<'BUILD_PL';
open my $build, '>', 'Build' or die "cannot write Build: $!";
print {$build} <<'BUILD';
#!/usr/bin/env perl
use strict;
use warnings;
exit 0 unless ($ARGV[0] || '') eq 'code';
require File::Path;
File::Path::make_path('lib/Generated');
open my $generated, '>', 'lib/Generated/PurePerl.pm' or die $!;
print {$generated} "package Generated::PurePerl; 1;\\n";
close $generated or die $!;
BUILD
close $build or die "cannot close Build: $!";
BUILD_PL
close $build_pl or die "cannot close Build.PL: $!";

my $distribution = bless {}, 'CPAN::Distribution';
ok($distribution->_perlonjava_run_build_code_action,
    'fallback runs the Module::Build code generation action');
ok(-f 'lib/Generated/PurePerl.pm',
    'generated pure-Perl module exists before fallback installation');

open my $broken_build_pl, '>', 'Build.PL' or die "cannot rewrite Build.PL: $!";
print {$broken_build_pl} "exit 1;\n";
close $broken_build_pl or die "cannot close Build.PL: $!";
ok(!$distribution->_perlonjava_run_build_code_action,
    'fallback does not continue when Module::Build configuration fails');

chdir $original_dir or die "cannot return to $original_dir: $!";
done_testing();

package Local::QuietCPANFrontend;
sub myprint { return 1 }
