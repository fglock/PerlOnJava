use strict;
use warnings;
use File::Spec;
use Test::More;

my $root = File::Spec->curdir;
my $lib = File::Spec->catdir($root, 'src', 'main', 'perl', 'lib');
my $source = File::Spec->catfile(
    $lib, 'PerlOnJava', 'CpanDistroprefs', 'Selenium-Remote-Driver.yml');

open my $source_fh, '<', $source or die "$source: $!";
my $source_text = do { local $/; <$source_fh> };
close $source_fh;

like($source_text, qr/^\s*distribution:\s*"\^TEODESIAN\/Selenium-Remote-Driver-"/m,
    'preference matches the Selenium::Remote::Driver distribution');
like($source_text, qr/^\s*commandline:\s*"JPERL_INTERPRETER=1 make test"/m,
    'preference runs the complete upstream suite under the interpreter');

my $config = File::Spec->catfile($lib, 'CPAN', 'Config.pm');
open my $config_fh, '<', $config or die "$config: $!";
my $config_text = do { local $/; <$config_fh> };
close $config_fh;
like($config_text,
    qr/'Selenium-Remote-Driver\.yml'\s*=>\s*'PerlOnJava\/CpanDistroprefs\/Selenium-Remote-Driver\.yml'/,
    'CPAN bootstrap registers the Selenium interpreter preference');

done_testing;
