use strict;
use warnings;
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use Test::More;

my $root = File::Spec->curdir;
my $lib = File::Spec->catdir($root, 'src', 'main', 'perl', 'lib');
my $prefs_source = File::Spec->catdir(
    $lib, 'PerlOnJava', 'CpanDistroprefs');

my @unsigned;
opendir my $prefs_dh, $prefs_source or die "$prefs_source: $!";
for my $name (sort grep { /\.yml\z/ } readdir $prefs_dh) {
    my $path = File::Spec->catfile($prefs_source, $name);
    open my $fh, '<', $path or die "$path: $!";
    my $content = do { local $/; <$fh> };
    close $fh;
    push @unsigned, $path unless $content =~ /PerlOnJava/;
}
closedir $prefs_dh;
is_deeply(\@unsigned, [], 'every bundled distropref has an ownership signature');

my $home = tempdir(CLEANUP => 1);
my $prefs_dir = File::Spec->catdir($home, 'cpan', 'prefs');
make_path($prefs_dir);
my $logger_pref = File::Spec->catfile($prefs_dir, 'Logger-Simple.yml');
my $retired_pref = File::Spec->catfile($prefs_dir, 'Test-Deep-JSON.yml');
my $retired_xml_pref =
    File::Spec->catfile($prefs_dir, 'XML-Filter-GenericChunk.yml');
open my $legacy, '>', $logger_pref or die "$logger_pref: $!";
print {$legacy} <<'YAML';
---
comment: |
  Logger::Simple depends on Object::InsideOut.
match:
  distribution: "^TSTANLEY/Logger-Simple-"
test:
  commandline: "JPERL_UNIMPLEMENTED=warn make test"
YAML
close $legacy;
open my $retired, '>', $retired_pref or die "$retired_pref: $!";
print {$retired} <<'YAML';
---
comment: PerlOnJava legacy dependency workaround
match:
  distribution: "^MOTEMEN/Test-Deep-JSON-"
YAML
close $retired;
open my $retired_xml, '>', $retired_xml_pref
    or die "$retired_xml_pref: $!";
print {$retired_xml} <<'YAML';
---
comment: PerlOnJava legacy dependency workaround
match:
  distribution: "^PHISH/XML-Filter-GenericChunk-"
YAML
close $retired_xml;

{
    local $ENV{PERLONJAVA_HOME} = $home;
    local @INC = ($lib, @INC);
    require CPAN::Config;
}

open my $updated, '<', $logger_pref or die "$logger_pref: $!";
my $updated_text = do { local $/; <$updated> };
close $updated;
like($updated_text, qr/JPERL_UNIMPLEMENTED:\s*warn/,
    'legacy Logger preference migrates to test.env');
unlike($updated_text, qr/commandline:/,
    'legacy shell-assignment commandline is removed');
ok(!-e $retired_pref,
    'retired PerlOnJava Test::Deep::JSON preference is removed');
ok(!-e $retired_xml_pref,
    'retired PerlOnJava XML::Filter::GenericChunk preference is removed');

open my $custom, '>', $retired_pref or die "$retired_pref: $!";
print {$custom} <<'YAML';
---
comment: local Test::Deep::JSON policy
match:
  distribution: "^MOTEMEN/Test-Deep-JSON-"
YAML
close $custom;

delete $INC{'CPAN/Config.pm'};
{
    local $ENV{PERLONJAVA_HOME} = $home;
    local @INC = ($lib, @INC);
    require CPAN::Config;
}
ok(-f $retired_pref,
    'user-owned Test::Deep::JSON preference survives bootstrap');

done_testing;
