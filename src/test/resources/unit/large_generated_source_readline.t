use strict;
use warnings;
use Test::More;
use Cwd qw(getcwd);
use File::Temp qw(tempfile);
use File::Path qw(make_path);

BEGIN {
    plan skip_all => 'Module::Build is not installed in this test environment'
        unless eval { require Module::Build; 1 };
}

# Module::Build scans generated Perl modules line by line while preparing
# distribution metadata and documentation. Keep this case large enough to
# exercise buffered reads without depending on a wall-clock threshold.
my $original_dir = getcwd();
my $tmpdir = File::Temp::tempdir(CLEANUP => 1);
END { chdir $original_dir if defined $original_dir; }
chdir $tmpdir or die "chdir $tmpdir: $!";
make_path('lib/Generated/LocaleData');
my $path = 'lib/Generated/LocaleData/LanguageMatching.pm';
open my $fh, '>', $path or die "create generated source: $!";
binmode $fh;
my $lines = 500_000;
print {$fh} "our \$generated_value = 123456789;\n" x $lines;
close $fh or die "close generated source: $!";

my $build = Module::Build->new(
    module_name => 'Generated::LocaleData',
    dist_version => '0.001',
    license => 'perl',
);
ok(-s $path > 10_000_000, 'fixture is a large generated Perl source');
ok(!$build->contains_pod($path),
    'Module::Build scans large generated source and finds no POD');

my ($pod_fh, $pod_path) = tempfile();
print {$pod_fh} "=head1 NAME\n\nGenerated::LocaleData - test fixture\n\n=cut\n";
close $pod_fh or die "close POD fixture: $!";
ok($build->contains_pod($pod_path), 'ordinary POD content is still detected');
unlink $path or die "unlink generated source: $!";
unlink $pod_path or die "unlink POD fixture: $!";

done_testing;
