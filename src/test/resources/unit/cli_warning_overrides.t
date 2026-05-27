use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);
use IPC::Open3;

my $tmpdir = tempdir(CLEANUP => 1);
my $seq = 0;

sub run_child {
    my ($switch, $code) = @_;
    my $script = "$tmpdir/child_" . (++$seq) . ".pl";
    open my $fh, '>', $script or die "Cannot write $script: $!";
    print {$fh} $code;
    close $fh or die "Cannot close $script: $!";

    my $jperl = $^X;
    if ($jperl eq 'jperl') {
        $jperl = $^O eq 'MSWin32' ? 'jperl.bat' : './jperl';
    }

    my ($in, $out);
    my $pid = open3($in, $out, undef, $jperl, $switch, $script);
    close $in;
    local $/;
    my $output = <$out> // '';
    close $out;
    waitpid($pid, 0);
    return $output;
}

is(
    run_child('-X', 'use warnings; my $b = $a + 0;'),
    '',
    '-X suppresses later use warnings',
);

is(
    run_child('-X', 'use 5.036; my $b = $a + 0;'),
    '',
    '-X suppresses warnings enabled by use VERSION',
);

like(
    run_child('-W', 'no warnings; my $b = $a + 0;'),
    qr/Use of uninitialized value .*in addition/,
    '-W overrides later no warnings',
);

done_testing();
