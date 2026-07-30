use strict;
use warnings;
use File::Find;
use File::Spec;
use Test::More;

my $root = File::Spec->catdir(File::Spec->curdir());
my $prefs_dir = File::Spec->catdir($root, 'src', 'main', 'perl', 'lib',
    'PerlOnJava', 'CpanDistroprefs');
my $config = File::Spec->catfile($root, 'src', 'main', 'perl', 'lib', 'CPAN',
    'Config.pm');

my @patch_refs;
find({
    wanted => sub {
        return unless -f $_ && /\.yml\z/;
        open my $fh, '<', $_ or die "$File::Find::name: $!";
        while (<$fh>) {
            push @patch_refs, $1 if /^\s*-\s*["']([^"']+\.patch)["']/;
        }
        close $fh;
    },
    no_chdir => 1,
}, $prefs_dir);

ok(@patch_refs, 'found bundled distropref patch references');
for my $path (@patch_refs) {
    unlike($path, qr{/[^/]*-\d[0-9A-Za-z.]*?/},
        "patch reference is not tied to a release directory: $path");
    like($path, qr{^[^/]+/[^/]+\.patch\z},
        "patch reference has stable distribution layout: $path");
}

open my $cfh, '<', $config or die "$config: $!";
my $config_text = do { local $/; <$cfh> };
close $cfh;
for my $path (@patch_refs) {
    like($config_text, qr/\[\s*'\Q$path\E',\s*\n\s*'PerlOnJava\/CpanPatches\//,
        "bootstrap installs distropref path: $path");
}

done_testing;
