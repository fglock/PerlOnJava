#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 3;
use File::Spec;
use File::Temp qw(tempdir);

SKIP: {
    skip 'direct executable shebang test is Unix-specific', 3
        if $^O eq 'MSWin32';

    my $dir = tempdir(CLEANUP => 1);
    my $script = File::Spec->catfile($dir, 'current-perl-script');
    open my $fh, '>', $script or die "open $script: $!";
    print {$fh} "#!$^X\n";
    print {$fh} 'print "current-perl-ok @ARGV\\n";', "\n";
    close $fh or die "close $script: $!";
    chmod 0755, $script or die "chmod $script: $!";

    ok(-x $script, 'generated $^X shebang script is executable');
    my $output = `$script alpha beta 2>&1`;
    is($?, 0, 'generated $^X shebang script runs directly');
    is($output, "current-perl-ok alpha beta\n", 'generated script receives argv');
}
