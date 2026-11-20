use strict;
use warnings;
use Test::More tests => 2;
use File::Temp qw(tempfile);

SKIP: {
    skip 'nested launcher is unavailable on Windows', 2
        if $^O eq 'MSWin32';

    my ($script_fh, $script_name) = tempfile(SUFFIX => '.pl');
    print {$script_fh} <<'CHILD';
use strict;
use warnings;

package GlobScalarReferenceDestruction;
sub DESTROY { eval { die "Farewell $_[0]" }; print $@ }

package main;
bless \$A::B, 'GlobScalarReferenceDestruction';
*A:: = \*B::;
CHILD
    close($script_fh) or die "close child script: $!";

    my ($output_fh, $output_name) = tempfile();
    open(my $saved_stdout, '>&', \*STDOUT) or die "save stdout: $!";
    open(STDOUT, '>&', $output_fh) or die "redirect stdout: $!";
    my $runner = $^X eq 'jperl' ? './jperl' : $^X;
    my $status = system('timeout', '60', $runner, $script_name);
    open(STDOUT, '>&', $saved_stdout) or die "restore stdout: $!";
    close($saved_stdout);

    seek($output_fh, 0, 0);
    my $output = do { local $/; <$output_fh> };
    close($output_fh);
    unlink($script_name);
    unlink($output_name);

    is($status, 0, 'child process exits cleanly');
    like($output, qr/^Farewell GlobScalarReferenceDestruction=SCALAR/m,
        'glob teardown runs DESTROY for a referenced package scalar');
}
