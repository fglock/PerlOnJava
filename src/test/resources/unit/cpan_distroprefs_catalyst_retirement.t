use strict;
use warnings;

use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use Test::More;

my $root = File::Spec->curdir;
my $lib = File::Spec->catdir($root, 'src', 'main', 'perl', 'lib');
my $bundled_pref = File::Spec->catfile(
    $lib, 'PerlOnJava', 'CpanDistroprefs', 'Catalyst-Runtime.yml');

ok(!-e $bundled_pref,
    'Catalyst runtime has no bundled test-skipping preference');

my $config_source = File::Spec->catfile($lib, 'CPAN', 'Config.pm');
open my $config_fh, '<', $config_source or die "$config_source: $!";
my $config_text = do { local $/; <$config_fh> };
close $config_fh;
unlike($config_text, qr/CpanDistroprefs\/Catalyst-Runtime[.]yml/,
    'Catalyst runtime skip is not registered for bootstrap');
like($config_text, qr/^\s*Catalyst-Runtime[.]yml\s*$/m,
    'retired PerlOnJava Catalyst preference is removed from existing homes');

my $home = tempdir(CLEANUP => 1);
my $prefs_dir = File::Spec->catdir($home, 'cpan', 'prefs');
make_path($prefs_dir);
my $pref = File::Spec->catfile($prefs_dir, 'Catalyst-Runtime.yml');

write_pref($pref, 'PerlOnJava distroprefs for Catalyst::Runtime');
load_cpan_config($home, $lib);
ok(!-e $pref, 'old PerlOnJava Catalyst skip preference is removed');

write_pref($pref, 'local Catalyst policy');
CPAN::Config::_bootstrap_prefs();
ok(-f $pref, 'user-owned Catalyst preference survives retirement bootstrap');

done_testing;

sub write_pref {
    my ($path, $comment) = @_;
    open my $fh, '>', $path or die "$path: $!";
    print {$fh} "---\ncomment: $comment\nmatch:\n  distribution: \"^JJNAPIORK/Catalyst-Runtime-\"\n";
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
