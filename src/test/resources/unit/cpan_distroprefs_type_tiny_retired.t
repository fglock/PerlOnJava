use strict;
use warnings;
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use Test::More;

my $root = File::Spec->curdir;
my $lib = File::Spec->catdir($root, 'src', 'main', 'perl', 'lib');
my $pref = File::Spec->catfile(
    $lib, 'PerlOnJava', 'CpanDistroprefs', 'Type-Tiny.yml');
my $patch = File::Spec->catfile(
    $lib, 'PerlOnJava', 'CpanPatches', 'Type-Tiny-2.010001',
    'SkipRegexCallbackTests.patch');
my $config = File::Spec->catfile($lib, 'CPAN', 'Config.pm');

ok(!-e $pref,
    'Type::Tiny no longer has a callback-test distropref');
ok(!-e $patch,
    'Type::Tiny no longer has a callback-test skip patch');

open my $config_fh, '<', $config or die "$config: $!";
my $config_text = do { local $/; <$config_fh> };
close $config_fh;

unlike($config_text, qr/['"]Type-Tiny\.yml['"]/,
    'CPAN bootstrap does not install a Type::Tiny distropref');
unlike($config_text, qr/\[\s*['"]Type-Tiny\/SkipRegexCallbackTests\.patch/,
    'CPAN bootstrap does not provide the retired callback patch');

my $home = tempdir(CLEANUP => 1);
my $prefs_dir = File::Spec->catdir($home, 'cpan', 'prefs');
my $patches_dir = File::Spec->catdir($home, 'cpan', 'patches');
my $stale_pref = File::Spec->catfile($prefs_dir, 'Type-Tiny.yml');
my $stale_patch = File::Spec->catfile(
    $patches_dir, 'Type-Tiny', 'SkipRegexCallbackTests.patch');
make_path($prefs_dir, File::Spec->catdir($patches_dir, 'Type-Tiny'));

open my $pref_fh, '>', $stale_pref or die "$stale_pref: $!";
print {$pref_fh} "comment: PerlOnJava retired callback policy\n";
close $pref_fh;
open my $patch_fh, '>', $stale_patch or die "$stale_patch: $!";
print {$patch_fh} "PerlOnJava retired callback patch\n";
close $patch_fh;

{
    local $ENV{PERLONJAVA_HOME} = $home;
    local @INC = ($lib, @INC);
    require CPAN::Config;
}
ok(!-e $stale_pref,
    'bootstrap removes an owned stale Type::Tiny distropref');
ok(!-e $stale_patch,
    'bootstrap removes the stale Type::Tiny callback patch');

open $pref_fh, '>', $stale_pref or die "$stale_pref: $!";
print {$pref_fh} "comment: user-owned Type::Tiny policy\n";
close $pref_fh;
delete $INC{'CPAN/Config.pm'};
{
    local $ENV{PERLONJAVA_HOME} = $home;
    local @INC = ($lib, @INC);
    require CPAN::Config;
}
ok(-f $stale_pref,
    'bootstrap preserves a user-owned Type::Tiny distropref');

done_testing;
