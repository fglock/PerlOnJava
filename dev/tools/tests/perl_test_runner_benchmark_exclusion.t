use strict;
use warnings;

use Cwd qw(getcwd);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use Test::More;

my $root = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $runner = File::Spec->catfile($root, 'dev', 'tools', 'perl_test_runner.pl');
my $temporary = tempdir(CLEANUP => 1);
my $core_dir = File::Spec->catdir($temporary, 'perl5_t', 't');
my $portable_dir = File::Spec->catdir($core_dir, 'op');
my $benchmark_dir = File::Spec->catdir($core_dir, 'benchmark');
my $fake_jperl = File::Spec->catfile($temporary, 'fake-jperl');
my $marker = File::Spec->catfile($temporary, 'launched');

make_path($portable_dir, $benchmark_dir);
write_file(File::Spec->catfile($portable_dir, 'portable.t'), "# portable fixture\n");
write_file(File::Spec->catfile($benchmark_dir, 'gh7094-speed-up-keys-on-empty-hash.t'), "# host-relative benchmark fixture\n");
write_file($fake_jperl, <<'FAKE_JPERL');
#!/usr/bin/env perl
use strict;
use warnings;

my $test_file = $ARGV[-1];
open my $marker, '>>', $ENV{RUNNER_MARKER}
    or die "cannot append marker: $!";
print {$marker} "$test_file\n";
close $marker;
print "1..1\n";
print "ok 1 - fake test\n";
FAKE_JPERL
chmod 0755, $fake_jperl or die "chmod $fake_jperl failed: $!";

my $old_dir = getcwd();
chdir $temporary or die "chdir $temporary failed: $!";
local $ENV{RUNNER_MARKER} = $marker;
my $output = qx{$^X "$runner" --jperl "$fake_jperl" --strict-exit --jobs 1 --timeout 10 perl5_t/t 2>&1};
my $status = $?;
chdir $old_dir or die "restore cwd failed: $!";

is($status, 0, 'runner succeeds with the host-relative benchmark excluded');
like($output, qr/Found 1 test files/, 'only the portable fixture is discovered');
open my $launched, '<', $marker or die "cannot read marker: $!";
my @launched = <$launched>;
close $launched;
is_deeply(\@launched, ["op/portable.t\n"],
    'runner never launches the host-relative benchmark fixture');

chdir $temporary or die "chdir $temporary failed: $!";
$output = qx{$^X "$runner" --jperl "$fake_jperl" --strict-exit --jobs 1 --timeout 10 perl5_t/t/benchmark/gh7094-speed-up-keys-on-empty-hash.t 2>&1};
$status = $?;
chdir $old_dir or die "restore cwd failed: $!";

isnt($status, 0, 'runner rejects an explicitly named host-relative benchmark');
like($output, qr/Excluding unsupported test file: perl5_t\/t\/benchmark\/gh7094-speed-up-keys-on-empty-hash\.t/,
    'direct benchmark exclusion is reported');
open $launched, '<', $marker or die "cannot read marker: $!";
@launched = <$launched>;
close $launched;
is_deeply(\@launched, ["op/portable.t\n"],
    'explicit benchmark selection does not launch the fixture');

done_testing;

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>', $path or die "cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "cannot close $path: $!";
}
