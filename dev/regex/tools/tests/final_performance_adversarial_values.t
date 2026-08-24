use strict;
use warnings;

use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use POSIX qw(_exit WNOHANG);
use Test::More;
use Time::HiRes qw(sleep);

use lib File::Spec->catdir($FindBin::Bin, '..', 'lib');
use PerlOnJava::PerformanceEvidence ();

for my $bad ('9' x 200, '1e9', 'Inf', 'NaN', '1.', '.1' . ('2' x 30)) {
    ok(!PerlOnJava::PerformanceEvidence::number($bad),
        "numeric gate rejects adversarial token $bad");
}
ok(PerlOnJava::PerformanceEvidence::number('0.20'),
    'bounded ratified decimal is accepted');
is(PerlOnJava::PerformanceEvidence::canonical_decimal('0.20'), '0.2',
    'ratified decimal canonicalization occurs only after lexical validation');

my @issues;
PerlOnJava::PerformanceEvidence::validate_ratified_policy(\@issues, {
    thresholds => {
        root_reflective_allocation_reduction => '9' x 200,
        live_heap_relative_allowance => '0.05',
        live_heap_absolute_allowance_bytes => '16777216',
        committed_heap_rss_review_ratio => '0.1',
    },
});
like(join("\n", @issues), qr/root_reflective_allocation_reduction/,
    'oversized threshold is rejected before arithmetic');

@issues = ();
my $parsed = PerlOnJava::PerformanceEvidence::parse_time(\@issues,
    'adversarial time', ('9' x 200) . " real\n1 user\n1 sys\n1 maximum resident set size\n");
is_deeply($parsed, {}, 'oversized captured timing decimal is rejected');
like(join("\n", @issues), qr/missing|malformed|bounded range/i,
    'oversized timing capture has a fail-closed diagnostic');

my $temporary = tempdir(CLEANUP => 1);
my $key = File::Spec->catfile($temporary, 'authority.key');
write_raw($key, 'x' x 64);
chmod 0644, $key or die $!;
my $ok = eval { PerlOnJava::PerformanceEvidence::read_authority_key($key); 1 };
ok(!$ok, 'group/world-readable authority key is rejected');
like($@, qr/exact mode 0600/, 'authority-key rejection names exact Unix mode');
chmod 0600, $key or die $!;
$ok = eval { PerlOnJava::PerformanceEvidence::read_authority_key($key); 1 };
ok($ok, 'private 0600 authority key is accepted');

SKIP: {
    skip 'Unix process groups are the declared A231/A232 contract', 2
        if $^O eq 'MSWin32';
    pipe my $reader, my $writer or die "Cannot create process-tree test pipe: $!";
    my $leader = fork();
    die "Cannot fork process-tree leader: $!" unless defined $leader;
    if ($leader == 0) {
        close $reader;
        eval { POSIX::setpgid(0, 0); 1 } or _exit(120);
        my $descendant = fork();
        _exit(121) unless defined $descendant;
        if ($descendant == 0) {
            $SIG{TERM} = 'IGNORE';
            sleep 30;
            _exit(0);
        }
        _exit(0);
    }
    close $writer;
    waitpid($leader, 0);
    is($? >> 8, 0, 'process-group leader exits while its descendant remains');
    PerlOnJava::PerformanceEvidence::terminate_group($leader, 1);
    my $eof = 0;
    for (1 .. 100) {
        my $rin = '';
        vec($rin, fileno($reader), 1) = 1;
        if (select($rin, undef, undef, 0.02) > 0) {
            my $count = sysread($reader, my $buffer, 1);
            $eof = 1 if defined($count) && $count == 0;
            last if $eof;
        }
    }
    ok($eof, 'TERM-then-KILL closes the surviving descendant tree after leader exit');
    close $reader;
}

done_testing;

sub write_raw {
    my ($path, $text) = @_;
    open my $fh, '>:raw', $path or die "Cannot write $path: $!";
    print {$fh} $text or die "Cannot write $path: $!";
    close $fh or die "Cannot close $path: $!";
}
