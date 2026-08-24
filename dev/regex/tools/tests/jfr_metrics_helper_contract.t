use strict;
use warnings;

use File::Spec;
use FindBin;
use Test::More;

my $root = File::Spec->rel2abs(
    File::Spec->catdir($FindBin::Bin, '..', '..', '..', '..'));
my $helper = File::Spec->catfile($root, 'dev', 'regex', 'tools',
    'JfrMetrics.java');
open my $fh, '<:raw', $helper or die "Cannot read $helper: $!";
my $source = do { local $/; <$fh> };
close $fh or die "Cannot close $helper: $!";

like($source,
    qr/owner\.equals\(GLOBAL_CODE_NEXT_OWNER\)\s*&&\s*method\.equals\("next"\)/,
    'wrapper allocation uses an exact owner and method match');
like($source,
    qr/GlobalVariable\$GlobalCodeRefMap\$1\$1/,
    'classifier names the nested owner found in the A220 JFR allocation site');
unlike($source, qr/owner\.startsWith/,
    'similarly named classes cannot enter through a prefix match');
like($source, qr/java\.lang\.reflect\.Field.*copy/s,
    'A220 Field.copy allocation site is explicit');
like($source, qr/java\.lang\.Class.*copyFields/s,
    'A220 Class.copyFields allocation site is explicit');
like($source, qr/MAX_PENDING_GC_IDS\s*=\s*4096/,
    'unpaired GC state has a hard checked bound');
like($source, qr/Math\.addExact/,
    'allocation and DataLoss accumulation rejects overflow');
like($source, qr/requireCompletePairing\(metrics\)/,
    'stream completion rejects unmatched collection/heap pairs at EOF');
like($source, qr/\\"gc_pairing_complete\\"/,
    'compact output explicitly records complete GC pairing');
unlike($source, qr/Files\.(?:readAllBytes|readString)/,
    'helper never slurps the raw recording');

my $java = find_java();
SKIP: {
    skip 'java executable is unavailable for source-mode self-test', 2
        unless defined $java;
    open my $pipe, '-|', $java, $helper, '--self-test'
        or die "Cannot launch helper self-test: $!";
    my $output = do { local $/; <$pipe> };
    close $pipe;
    is($? >> 8, 0, 'source-mode helper compiles and its focused self-test passes');
    is($output, "REGEX_IMPLEMENTATION_JFR_METRICS_SELF_TEST ok\n",
        'self-test proves exact positives, lookalike negatives, overflow, and GC bound');
}

done_testing;

sub find_java {
    if (defined($ENV{JAVA_HOME})) {
        my $candidate = File::Spec->catfile($ENV{JAVA_HOME}, 'bin',
            $^O eq 'MSWin32' ? 'java.exe' : 'java');
        return $candidate if -x $candidate;
    }
    for my $directory (File::Spec->path()) {
        my $candidate = File::Spec->catfile($directory,
            $^O eq 'MSWin32' ? 'java.exe' : 'java');
        return $candidate if -x $candidate;
    }
    return;
}
