use strict;
use warnings;

use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use Test::More;

my $root = File::Spec->curdir;
my $lib = File::Spec->catdir($root, 'src', 'main', 'perl', 'lib');
my $bundled_moo = File::Spec->catfile(
    $lib, 'PerlOnJava', 'CpanDistroprefs', 'Moo.yml');

ok(!-e $bundled_moo,
    'retired behaviorally inert Moo preference has no bundled source');

my $config_source = File::Spec->catfile($lib, 'CPAN', 'Config.pm');
open my $config_fh, '<', $config_source or die "$config_source: $!";
my $config_text = do { local $/; <$config_fh> };
close $config_fh;
unlike($config_text, qr/CpanDistroprefs\/Moo[.]yml/,
    'retired Moo preference has no active bootstrap registration');

my $home = tempdir(CLEANUP => 1);
my $prefs_dir = File::Spec->catdir($home, 'cpan', 'prefs');
make_path($prefs_dir);
my $moo_pref = File::Spec->catfile($prefs_dir, 'Moo.yml');

write_moo_pref($moo_pref, 'PerlOnJava legacy Moo match-only preference');
load_cpan_config($home, $lib);
ok(!-e $moo_pref, 'retired PerlOnJava Moo preference is removed');

write_moo_pref($moo_pref, 'local Moo policy');
load_cpan_config($home, $lib);
ok(-f $moo_pref, 'user-owned Moo preference survives retirement bootstrap');

done_testing;

sub write_moo_pref {
    my ($path, $comment) = @_;
    open my $fh, '>', $path or die "$path: $!";
    print {$fh} "---\ncomment: $comment\nmatch:\n  distribution: \"^HAARG/Moo-\"\n";
    close $fh or die "$path: $!";
}

sub load_cpan_config {
    my ($home, $lib) = @_;
    delete $INC{'CPAN/Config.pm'};
    local $ENV{PERLONJAVA_HOME} = $home;
    local @INC = ($lib, @INC);
    {
        no warnings 'redefine';
        require CPAN::Config;
    }
}
