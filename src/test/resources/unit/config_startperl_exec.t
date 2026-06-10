use strict;
use warnings;
use Test::More tests => 3;
use Config;
use File::Spec;
use File::Temp qw(tempdir);

SKIP: {
    skip 'direct executable shebang test is Unix-specific', 3
        if $Config{osname} eq 'MSWin32' || $^O eq 'MSWin32';

    my $dir = tempdir(CLEANUP => 1);
    my $script = File::Spec->catfile($dir, 'startperl-script');

    open my $fh, '>', $script or die "open $script: $!";
    print {$fh} $Config{startperl}, "\n";
    print {$fh} 'print "startperl-ok @ARGV\n";', "\n";
    close $fh or die "close $script: $!";
    chmod 0755, $script or die "chmod $script: $!";

    ok(-x $script, 'generated script is executable');

    my $output = `$script alpha beta 2>&1`;
    my $status = $?;

    is($status, 0, 'generated script runs directly');
    is($output, "startperl-ok alpha beta\n", 'generated script receives argv');
}
