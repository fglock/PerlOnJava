package PerlOnJava::Distroprefs::LocaleCLDR;

use strict;
use warnings;

sub test_phase {
    require ExtUtils::Command::MM;

    my @tests = grep { !m{(?:^|/)06-Segmentation\.t\z} } glob 't/*.t';
    die "Locale::CLDR test files were not found\n" unless @tests;

    local $Test::Harness::Switches;
    local @ARGV = @tests;
    ExtUtils::Command::MM::test_harness(0, 'blib/lib', 'blib/arch');
    return 1;
}

1;
