use strict;
use warnings;
use Archive::Tar;
use Cwd qw(getcwd);
use File::Path qw(make_path);
use File::Temp qw(tempdir);
use Test::More;

# Regression for #1514: release archive paths follow Perl's changed cwd.
my $original_dir = getcwd();
my $tmpdir = tempdir(CLEANUP => 1);
END {
    chdir $original_dir if defined $original_dir;
}

make_path("$tmpdir/release/source");
chdir "$tmpdir/release/source" or die "chdir to release source: $!";

my $tar = Archive::Tar->new;
$tar->add_data('DZT-Sample/lib/DZT/Sample.pm', "package DZT::Sample; 1;\n");
ok($tar->write('DZT-Sample-0.001.tar.gz', 9),
    'writes a compressed release archive using a relative path');
ok(-f 'DZT-Sample-0.001.tar.gz',
    'archive is written in the changed working directory');

my $readback = Archive::Tar->new;
ok($readback->read('DZT-Sample-0.001.tar.gz'),
    'relative archive path can be read after writing');
is($readback->get_content('DZT-Sample/lib/DZT/Sample.pm'),
    "package DZT::Sample; 1;\n",
    'release archive remains available to later consumers');

done_testing;
