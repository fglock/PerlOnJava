use strict;
use warnings;

use File::Spec;
use FindBin;
use JSON::PP;
use Test::More;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $worker = File::Spec->catfile(
    $root, 'dev', 'bench', 'performance_workload.pl');

open my $command, '-|', $^X, $worker,
    '--workload', 'closure',
    '--window-seconds', '1',
    '--windows', '1',
    '--warmup-min', '1',
    '--warmup-max', '1'
    or die "cannot start workload: $!";
my $output = do { local $/; <$command> };
ok(close $command, 'workload process completes') or diag($output // '');

my $document = JSON::PP->new->decode($output);
is($document->{schema_version}, 1, 'schema version is stable');
is($document->{workload}, 'closure', 'requested workload is recorded');
is($document->{semantic_checksum}, '9216', 'closure result is checksummed');
is(scalar @{$document->{warmup_windows}}, 1, 'warmup window is emitted');
is(scalar @{$document->{windows}}, 1, 'measurement window is emitted');

my $window = $document->{windows}[0];
ok($window->{elapsed_seconds} >= 1, 'measurement has a full wall-time window');
ok(defined $window->{process_cpu_seconds}, 'measurement records process CPU time');
ok($window->{operations} > 0, 'measurement records completed operations');
ok($window->{throughput} > 0, 'measurement records throughput');

done_testing;
