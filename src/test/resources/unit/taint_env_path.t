use Test::More tests => 4;
use File::Temp qw(tempfile);

SKIP: {
    skip 'nested jperl launcher is unavailable in Windows Maven CI', 4
        if $^O eq 'MSWin32';

    my ($fh, $name) = tempfile();
    my $jperl = $^X eq 'jperl' ? './jperl' : $^X;
    my $code = join "\n",
        'use Scalar::Util qw(tainted);',
        'print ${^TAINT} ? "taint\n" : "clean\n";',
        'print tainted($ENV{PATH}) ? "env-tainted\n" : "env-clean\n";',
        'my $copy = $ENV{PATH};',
        'print tainted($copy) ? "copy-tainted\n" : "copy-clean\n";';

    open(my $saved_stdout, '>&', \*STDOUT) or die "save stdout: $!";
    open(STDOUT, '>&', $fh) or die "redirect stdout: $!";
    my $status = system($jperl, '-T', '-e', $code);
    open(STDOUT, '>&', $saved_stdout) or die "restore stdout: $!";
    close($saved_stdout);

    seek($fh, 0, 0);
    my $output = do { local $/; <$fh> };
    close($fh);
    unlink $name;

    is($status, 0, 'nested jperl -T exits successfully');
    like($output, qr/^taint$/m, 'taint mode is enabled from -T');
    like($output, qr/^env-tainted$/m, 'PATH is tainted under -T');
    like($output, qr/^copy-tainted$/m, 'taint flag is copied with scalar assignment');
}
