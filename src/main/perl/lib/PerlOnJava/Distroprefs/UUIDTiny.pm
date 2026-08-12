package PerlOnJava::Distroprefs::UUIDTiny;

use strict;
use warnings;

sub test_phase {
    require ExtUtils::Command::MM;

    my @tests = grep { !m{(?:^|/)03-UUID-fork\.t\z} } glob 't/*.t';
    die "UUID::Tiny test files were not found\n" unless @tests;

    local $Test::Harness::Switches;
    local @ARGV = @tests;
    ExtUtils::Command::MM::test_harness(0, 'blib/lib', 'blib/arch');
    return 1;
}

1;
