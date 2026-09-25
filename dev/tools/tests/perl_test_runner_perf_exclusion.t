#!/usr/bin/env perl
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
my $perf_dir = File::Spec->catdir($temporary, 'perl5_t', 't', 'perf');
my $fake_jperl = File::Spec->catfile($temporary, 'fake-jperl');
my $marker = File::Spec->catfile($temporary, 'launched');

make_path($perf_dir);
write_file(File::Spec->catfile($perf_dir, 'opcount.t'), "# unsupported perf fixture\n");
write_file(File::Spec->catfile($perf_dir, 'optree.t'), "# unsupported perf fixture\n");
write_file($fake_jperl, <<'FAKE_JPERL');
#!/usr/bin/env perl
use strict;
use warnings;
open my $marker, '>>', $ENV{RUNNER_MARKER} or die "cannot append marker: $!";
print {$marker} "$ARGV[-1]\n";
close $marker;
print "1..1\n";
print "ok 1 - fake test\n";
FAKE_JPERL
chmod 0755, $fake_jperl or die "chmod $fake_jperl failed: $!";

my $old_dir = getcwd();
chdir $temporary or die "chdir $temporary failed: $!";
local $ENV{RUNNER_MARKER} = $marker;
my $output = qx{$^X "$runner" --jperl "$fake_jperl" --strict-exit --jobs 1 --timeout 10 perl5_t/t/perf 2>&1};
my $status = $?;
chdir $old_dir or die "restore cwd failed: $!";

isnt($status, 0, 'a directory containing only unsupported perf tests is rejected');
like($output, qr/Error: No test files found/,
    'excluded perf directory is not schedulable');
unlike($output, qr/Found \d+ test files/, 'excluded perf tests are not scheduled');

chdir $temporary or die "chdir $temporary failed: $!";
$output = qx{$^X "$runner" --jperl "$fake_jperl" --strict-exit --jobs 1 --timeout 10 perl5_t/t/perf/opcount.t perl5_t/t/perf/optree.t 2>&1};
$status = $?;
chdir $old_dir or die "restore cwd failed: $!";
isnt($status, 0, 'directly selected unsupported perf tests are rejected');
like($output, qr/Excluding unsupported test file: perl5_t\/t\/perf\/opcount\.t/,
    'opcount exclusion is reported');
like($output, qr/Excluding unsupported test file: perl5_t\/t\/perf\/optree\.t/,
    'optree exclusion is reported');

done_testing;

sub write_file {
    my ($path, $contents) = @_;
    open my $fh, '>', $path or die "cannot write $path: $!";
    print {$fh} $contents;
    close $fh or die "cannot close $path: $!";
}
