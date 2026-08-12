package PerlOnJava::Distroprefs::MooXClassAttribute;

use strict;
use warnings;

sub test_phase {
    require ExtUtils::Command::MM;

    my @tests = grep { !m{(?:^|/)21hook_appl_moose\.t\z} } glob 't/*.t';
    die "MooX::ClassAttribute test files were not found\n" unless @tests;

    local $Test::Harness::Switches;
    local @ARGV = @tests;
    ExtUtils::Command::MM::test_harness(0, 'blib/lib', 'blib/arch');
    return 1;
}

1;
