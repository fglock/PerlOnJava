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
my $test_dir = File::Spec->catdir(
    $temporary, 'perl5_t', 't', 're');
my $test_file = File::Spec->catfile($test_dir, 'pat_advanced.t');
my $factor_file = File::Spec->catfile($temporary, 'factor.txt');

make_path($test_dir) or die "make_path $test_dir failed: $!";
write_file($test_file, "1..1\nok 1\n");
write_file($fake_jperl, <<'FAKE_JPERL');
#!/usr/bin/env perl
use strict;
use warnings;
open my $fh, '>>', $ENV{WATCHDOG_FACTOR_FILE}
    or die "cannot write watchdog factor: $!\n";
print {$fh} ($ENV{PERL_TEST_TIMEOUT_FACTOR} // ''), "\n";
close $fh or die "cannot close watchdog factor: $!\n";
print "1..1\nok 1 - fake test completed\n";
FAKE_JPERL
chmod 0755, $fake_jperl or die "chmod $fake_jperl failed: $!";

local $ENV{WATCHDOG_FACTOR_FILE} = $factor_file;
delete local $ENV{PERL_TEST_TIMEOUT_FACTOR};
ok(run_runner(), 'pat_advanced runs with the runner minimum');

$ENV{PERL_TEST_TIMEOUT_FACTOR} = 9;
ok(run_runner(), 'a larger caller watchdog factor is preserved');

open my $factor_fh, '<', $factor_file or die "cannot read $factor_file: $!";
chomp(my @factors = <$factor_fh>);
close $factor_fh;
is_deeply(\@factors, [6, 9],
    'pat_advanced receives a stable minimum without lowering caller policy');

done_testing();

sub run_runner {
    open my $command, '-|', $^X, $runner,
        '--jperl', $fake_jperl,
        '--jobs', '1',
        '--timeout', '1',
        $test_file
        or die "cannot start test runner: $!";
    my $output = do { local $/; <$command> };
    my $closed = close $command;
    diag($output // '') unless $closed;
    return $closed;
}

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>', $path or die "cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "cannot close $path: $!";
}
