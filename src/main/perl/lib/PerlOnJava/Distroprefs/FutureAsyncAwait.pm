package PerlOnJava::Distroprefs::FutureAsyncAwait;

use strict;
use warnings;

our $VERSION = '0.01';

sub prepare {
    open my $fh, '>>', 'Makefile' or die "Cannot create Makefile: $!\n";
    close $fh;
    return 0;
}

sub noop { 0 }

sub test_phase {
    require ExtUtils::Command::MM;

    local @ARGV = sort glob 't/*.t';
    die "Future::AsyncAwait distribution contains no tests\n" unless @ARGV;

    local @INC = ('inc', @INC);
    ExtUtils::Command::MM::test_harness(0, 'blib/lib', 'blib/arch');
    return 0;
}

1;
