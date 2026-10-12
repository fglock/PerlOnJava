use strict;
use warnings;
use Cwd qw(getcwd);
use File::Path qw(make_path);
use File::Temp qw(tempdir);
use Test::More;

BEGIN {
    plan skip_all => 'Module::Build is not installed in this test environment'
        unless eval { require Module::Build; 1 };
}

# Regression for #1432: nested declared modules are included in blib.
my $original_dir = getcwd();
my $tmpdir = tempdir(CLEANUP => 1);
END {
    chdir $original_dir if defined $original_dir;
}

chdir $tmpdir or die "chdir $tmpdir: $!";
make_path('lib/AudioFile/Info/MP3');

for my $file (
    ['lib/AudioFile/Info/Build.pm',
     "package AudioFile::Info::Build; our \$VERSION = '0.001'; 1;\n"],
    ['lib/AudioFile/Info/MP3/Tag.pm',
     "package AudioFile::Info::MP3::Tag; our \$VERSION = '0.001'; 1;\n"],
) {
    open my $fh, '>', $file->[0] or die "create $file->[0]: $!";
    print {$fh} $file->[1];
    close $fh or die "close $file->[0]: $!";
}

my $build = Module::Build->new(
    module_name => 'AudioFile::Info::MP3::Tag',
    dist_version => '0.001',
    license => 'perl',
);

my $pm_files = $build->find_pm_files;
ok(exists $pm_files->{'lib/AudioFile/Info/Build.pm'},
    'build helper module is discovered');
ok(exists $pm_files->{'lib/AudioFile/Info/MP3/Tag.pm'},
    'declared nested plugin module is discovered');

$build->process_pm_files('pm');
ok(-f 'blib/lib/AudioFile/Info/Build.pm',
    'Module::Build stages its helper module in blib');
ok(-f 'blib/lib/AudioFile/Info/MP3/Tag.pm',
    'Module::Build stages the nested plugin module in blib');

done_testing;
