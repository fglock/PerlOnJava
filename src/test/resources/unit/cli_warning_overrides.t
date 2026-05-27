use strict;
use warnings;
use Test::More;
use File::Temp qw(tempdir);

my $tmpdir = tempdir(CLEANUP => 1);
my $seq = 0;

sub shell_quote {
    my ($arg) = @_;
    $arg =~ s/'/'\\''/g;
    return "'$arg'";
}

sub run_child {
    my ($switch, $code) = @_;
    my $script = "$tmpdir/child_" . (++$seq) . ".pl";
    open my $fh, '>', $script or die "Cannot write $script: $!";
    print {$fh} $code;
    close $fh or die "Cannot close $script: $!";

    my $jperl = $^X eq 'jperl' ? './jperl' : $^X;
    my $cmd = join ' ', map { shell_quote($_) } ($jperl, $switch, $script);
    open my $pipe, "$cmd 2>&1 |" or die "Cannot run $cmd: $!";
    local $/;
    my $output = <$pipe>;
    close $pipe;
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
