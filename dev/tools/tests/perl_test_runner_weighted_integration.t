use strict;
use warnings;

use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use Test::More;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $runner = File::Spec->catfile(
    $root, 'dev', 'tools', 'perl_test_runner.pl');
my $temporary = tempdir(CLEANUP => 1);
my $fake_jperl = File::Spec->catfile($temporary, 'fake-jperl');
my $order_file = File::Spec->catfile($temporary, 'launch-order.txt');
my $normal_test = File::Spec->catfile($temporary, 'unit', 'short.t');
my $heavy_test = File::Spec->catfile(
    $temporary, 'perl5_t', 't', 're', 'pat_psycho.t');

make_path(File::Spec->catdir($temporary, 'unit'));
make_path(File::Spec->catdir($temporary, 'perl5_t', 't', 're'));
write_file($normal_test, "# fake normal test\n");
write_file($heavy_test, "# fake heavy test\n");
write_file($fake_jperl, <<'FAKE_JPERL');
#!/usr/bin/env perl
use strict;
use warnings;

open my $fh, '>>', $ENV{RUNNER_ORDER_FILE}
    or die "cannot open launch-order file: $!\n";
print {$fh} "$ARGV[0]\n";
close $fh;
print "1..1\nok 1 - fake semantic result\n";
FAKE_JPERL
chmod 0755, $fake_jperl or die "chmod $fake_jperl failed: $!";

local $ENV{RUNNER_ORDER_FILE} = $order_file;
open my $command, '-|', $^X, $runner,
    '--jperl', $fake_jperl,
    '--strict-exit',
    '--jobs', '1',
    '--timeout', '10',
    $normal_test,
    $heavy_test
    or die "cannot start test runner: $!";
my $runner_output = do { local $/; <$command> };
ok(close $command, 'weighted runner integration fixture passes')
    or diag($runner_output // '');

open my $order_fh, '<', $order_file
    or die "cannot read $order_file: $!";
chomp(my @launch_order = <$order_fh>);
close $order_fh;

is(scalar(@launch_order), 2, 'both fake files ran');
like($launch_order[0], qr{pat_psycho\.t$},
    'known long-running file launches before earlier ordinary input');
like($launch_order[1], qr{short\.t$},
    'ordinary file launches after the heavy file at budget one');

done_testing;

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>', $path or die "cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "cannot close $path: $!";
}
