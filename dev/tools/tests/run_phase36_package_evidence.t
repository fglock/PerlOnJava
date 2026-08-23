use strict;
use warnings;

use Config;
use Cwd qw(abs_path);
use Digest::SHA qw(sha256_hex);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use JSON::PP;
use Test::More;

my $repository = abs_path(File::Spec->catdir($FindBin::Bin, '..', '..', '..'));
my $producer = File::Spec->catfile($repository, 'dev', 'tools',
    'run_phase36_package_evidence.pl');
my $system_perl = abs_path($Config{perlpath});
my $temporary = tempdir(CLEANUP => 1);
my $commit = 'a' x 40;

subtest 'happy path publishes one complete atomic artifact' => sub {
    my $fixture = fixture('happy');
    my ($status, $text) = run_producer($fixture, 3);
    is($status, 0, 'synthetic package is accepted') or diag $text;
    my @outputs = entries($fixture->{output});
    is_deeply(\@outputs, ['package-evidence.json'], 'exactly one artifact is published');
    my $evidence = load_json(File::Spec->catfile($fixture->{output}, $outputs[0]));
    is($evidence->{status}, 'pass', 'artifact records pass status');
    is($evidence->{identity}{source_commit}, $commit, 'exact commit is bound');
    like($evidence->{identity}{jar_sha256}, qr/\A[0-9a-f]{64}\z/,
        'JAR identity is hashed');
    like($evidence->{trees}{install_dist}{tree_sha256}, qr/\A[0-9a-f]{64}\z/,
        'complete install tree is hashed');
    like($evidence->{trees}{debian}{tree_sha256}, qr/\A[0-9a-f]{64}\z/,
        'complete Debian tree is hashed');
    my @names = map { $_->{name} } @{$evidence->{commands}};
    ok(grep($_ eq 'make-deb', @names), 'bounded make command is recorded');
    ok(grep($_ eq 'verify-distribution', @names), 'distribution verifier is invoked');
    ok(grep($_ eq 'verify-packaging', @names), 'Joni/SBOM verifier is invoked');
    ok(grep($_ eq 'verify-notice-license', @names), 'notice verifier is invoked');
    ok(grep($_ eq 'dpkg-extract', @names), 'Debian package is inspected');
    ok(!grep(!/\A[0-9a-f]{64}\z/, map { $_->{argv_sha256} } @{$evidence->{commands}}),
        'every complete argv is hashed');
    ok(!grep(!/\A[0-9a-f]{64}\z/, map { $_->{log_sha256} } @{$evidence->{commands}}),
        'every command log is hashed');
};

for my $case (
    ['tamper', 'tamper', qr/dirty or mutated/],
    ['path escape', 'path', qr/unexpected symlink/],
    ['timeout', 'timeout', qr/timed out/],
    ['malformed package', 'malformed', qr/dpkg-info exited nonzero/],
    ['verifier atomicity', 'verifier-fail', qr/verify-notice-license exited nonzero/],
) {
    subtest "$case->[0] fails closed without partial publication" => sub {
        my $fixture = fixture($case->[1]);
        my ($status, $text) = run_producer($fixture,
            $case->[1] eq 'timeout' ? 1 : 3);
        isnt($status, 0, 'producer rejects boundary');
        like($text, $case->[2], 'diagnostic identifies boundary');
        is_deeply([entries($fixture->{output})], [], 'sealed output remains empty');
    };
}

subtest 'stale package output is rejected before make executes' => sub {
    my $fixture = fixture('stale');
    make_path(File::Spec->catdir($fixture->{source}, 'build', 'install'));
    my ($status, $text) = run_producer($fixture, 2);
    isnt($status, 0, 'stale output is rejected');
    like($text, qr/Stale preexisting package output/, 'stale diagnostic is explicit');
    ok(!-e File::Spec->catfile($fixture->{source}, 'MAKE-RAN'),
        'make was not allowed to clean or reuse stale output');
    is_deeply([entries($fixture->{output})], [], 'no evidence is published');
};

done_testing;

sub run_producer {
    my ($fixture, $timeout) = @_;
    my $log = File::Spec->catfile($fixture->{base}, 'producer.log');
    my @argv = ($system_perl, $producer,
        '--source-root', $fixture->{source}, '--expected-commit', $commit,
        '--output-root', $fixture->{output}, '--make', $fixture->{tools}{make},
        '--perl', $system_perl, '--git', $fixture->{tools}{git},
        '--dpkg-deb', $fixture->{tools}{dpkg}, '--java', $fixture->{tools}{java},
        '--jar', $fixture->{tools}{jar},
        '--timeout', $timeout);
    my $pid = fork();
    die "fork failed: $!" unless defined $pid;
    if (!$pid) {
        open STDOUT, '>:raw', $log or die $!;
        open STDERR, '>&', \*STDOUT or die $!;
        exec { $argv[0] } @argv;
        die $!;
    }
    waitpid($pid, 0);
    return ($? >> 8, read_file($log));
}

sub fixture {
    my ($scenario) = @_;
    my $base = File::Spec->catdir($temporary, "$scenario-" . int(rand(1_000_000)));
    my $source = File::Spec->catdir($base, 'source');
    my $output = File::Spec->catdir($base, 'output');
    my $tools = File::Spec->catdir($base, 'tools');
    make_path($source, $output, $tools,
        File::Spec->catdir($source, 'dev', 'tools'),
        File::Spec->catdir($source, 'gradle', 'wrapper'),
        File::Spec->catdir($source, 'third_party', 'joni'),
        File::Spec->catdir($source, 'third_party', 'licenses'));
    write_file(File::Spec->catfile($source, 'SCENARIO'), "$scenario\n");
    write_file(File::Spec->catfile($source, 'EXPECTED_COMMIT'), "$commit\n");
    write_file(File::Spec->catfile($source, 'Makefile'), "deb:\n\ttrue\n");
    write_file(File::Spec->catfile($source, 'build.gradle'), "version = '5.44.0'\n");
    write_file(File::Spec->catfile($source, 'settings.gradle'), "rootProject.name='x'\n");
    write_file(File::Spec->catfile($source, 'gradle', 'wrapper',
        'gradle-wrapper.properties'), "distributionUrl=fake\n");
    write_file(File::Spec->catfile($source, 'third_party', 'joni', 'LICENSE'), "joni license\n");
    write_file(File::Spec->catfile($source, 'third_party', 'joni',
        'PERLONJAVA-NOTICE.md'), "joni notice\n");
    write_file(File::Spec->catfile($source, 'third_party', 'licenses',
        'jcodings-LICENSE.txt'), "jcodings license\n");

    my $make = write_executable(File::Spec->catfile($tools, 'make'), <<'MAKE');
#!/usr/bin/perl
use strict; use warnings;
use File::Path qw(make_path);
use JSON::PP;
my $root;
for (my $i = 0; $i < @ARGV; $i++) { $root = $ARGV[$i + 1] if $ARGV[$i] eq '-C' }
die "missing -C" unless $root;
open my $ran, '>', "$root/MAKE-RAN" or die $!; print {$ran} "yes\n"; close $ran;
open my $sf, '<', "$root/SCENARIO" or die $!; chomp(my $scenario = <$sf>); close $sf;
sleep 5 if $scenario eq 'timeout';
my $install = "$root/build/install/perlonjava";
my $package = "$root/.fake-package-tree/opt/perlonjava";
for my $dir ($install, $package) {
    make_path("$dir/lib", "$dir/bin", "$dir/share/licenses", "$dir/share/sbom");
}
make_path("$root/target", "$root/build/reports", "$root/build/distributions");
open my $cf, '<', "$root/EXPECTED_COMMIT" or die $!; chomp(my $commit = <$cf>); close $cf;
my $jar = "JAR:$commit\n";
my $sbom = JSON::PP->new->canonical->encode({ bomFormat => 'CycloneDX', components => [{
    group => 'org.perlonjava.fork', name => 'joni-fork', properties => [{
        name => 'perlonjava:source-commit', value => $commit }]}] });
sub put { my ($path, $bytes) = @_; open my $fh, '>:raw', $path or die $!;
    print {$fh} $bytes; close $fh or die $! }
put("$root/target/perlonjava-5.44.0.jar", $jar);
put("$root/build/reports/sbom.json", $sbom);
for my $dir ($install, $package) {
    put("$dir/lib/perlonjava-5.44.0.jar", $jar);
    put("$dir/bin/perlonjava", "launcher\n");
    put("$dir/bin/perlonjava.bat", "launcher\n");
    put("$dir/share/sbom/sbom.json", $sbom);
    for my $name ('joni-LICENSE.txt', 'joni-PERLONJAVA-NOTICE.md', 'jcodings-LICENSE.txt') {
        put("$dir/share/licenses/$name", "$name\n");
    }
}
symlink('../../outside', "$install/lib/escape") if $scenario eq 'path';
put("$root/build/distributions/perlonjava_5.44.0_all.deb", "DEB\n");
MAKE

    my $git = write_executable(File::Spec->catfile($tools, 'git'), <<'GIT');
#!/usr/bin/perl
use strict; use warnings;
my $root;
if (@ARGV >= 2 && $ARGV[0] eq '-C') { $root = $ARGV[1]; splice @ARGV, 0, 2 }
if (@ARGV >= 2 && $ARGV[0] eq 'rev-parse' && $ARGV[1] eq 'HEAD') {
    open my $fh, '<', "$root/EXPECTED_COMMIT" or die $!; print while <$fh>; exit 0;
}
if (@ARGV && $ARGV[0] eq 'status') { print " M protected\n" if -e "$root/DIRTY"; exit 0 }
die "unexpected fake git argv: @ARGV";
GIT

    my $dpkg = write_executable(File::Spec->catfile($tools, 'dpkg-deb'), <<'DPKG');
#!/usr/bin/perl
use strict; use warnings;
use File::Find qw(find);
use File::Path qw(make_path);
use File::Basename qw(dirname);
my ($mode, $deb, $destination) = @ARGV;
my $root = dirname(dirname(dirname($deb)));
open my $sf, '<', "$root/SCENARIO" or die $!; chomp(my $scenario = <$sf>); close $sf;
exit 9 if $mode eq '--info' && $scenario eq 'malformed';
if ($mode eq '--info') { print " Package: perlonjava\n"; exit 0 }
if ($mode eq '--contents') {
    print "-rw-r--r-- root/root 4 2026-01-01 00:00 ./opt/perlonjava/lib/perlonjava-5.44.0.jar\n";
    exit 0;
}
die "bad dpkg mode" unless $mode eq '--extract';
my $tree = "$root/.fake-package-tree";
find({ no_chdir => 1, wanted => sub {
    return if $_ eq $tree;
    my $relative = substr($_, length($tree) + 1);
    my $to = "$destination/$relative";
    if (-d $_) { make_path($to); return }
    make_path(dirname($to));
    open my $in, '<:raw', $_ or die $!; open my $out, '>:raw', $to or die $!;
    local $/; print {$out} <$in>; close $in; close $out;
}}, $tree);
exit 0;
DPKG
    my $jar = write_executable(File::Spec->catfile($tools, 'jar'), <<'JAR');
#!/usr/bin/perl
exit 0;
JAR
    my $java = write_executable(File::Spec->catfile($tools, 'java'), <<'JAVA');
#!/usr/bin/perl
exit 0;
JAVA

    write_executable(File::Spec->catfile($source, 'dev', 'tools',
        'verify-joni-distribution.pl'), verifier_program('distribution'));
    write_executable(File::Spec->catfile($source, 'dev', 'tools',
        'verify-joni-packaging.pl'), verifier_program('packaging'));
    write_executable(File::Spec->catfile($source, 'dev', 'tools',
        'verify_phase36_notice_license.pl'), verifier_program('notice'));

    return { base => $base, source => abs_path($source), output => abs_path($output),
        tools => { make => abs_path($make), git => abs_path($git),
            dpkg => abs_path($dpkg), java => abs_path($java), jar => abs_path($jar) } };
}

sub verifier_program {
    my ($kind) = @_;
    return <<"VERIFY";
#!/usr/bin/perl
use strict; use warnings;
use FindBin; use Digest::SHA qw(sha256_hex); use JSON::PP;
my \$root = "\$FindBin::Bin/../..";
open my \$sf, '<', "\$root/SCENARIO" or die \$!; chomp(my \$scenario = <\$sf>); close \$sf;
if ('$kind' eq 'packaging' && \$scenario eq 'tamper') {
    open my \$fh, '>', "\$root/DIRTY" or die \$!; print {\$fh} "dirty\n"; close \$fh;
}
exit 7 if '$kind' eq 'notice' && \$scenario eq 'verifier-fail';
if ('$kind' eq 'notice') {
    my %o; while (\@ARGV) { my \$key = shift \@ARGV; next if \$key eq '--strict'; \$key =~ s/^--//; \$o{\$key} = shift \@ARGV }
    sub bytes { open my \$fh, '<:raw', \$_[0] or die \$!; local \$/; return <\$fh> }
    open my \$out, '>:raw', \$o{output} or die \$!;
    print {\$out} JSON::PP->new->canonical->encode({ verified => JSON::PP::true,
        jar_sha256 => sha256_hex(bytes(\$o{jar})), sbom_sha256 => sha256_hex(bytes(\$o{sbom})) });
    close \$out;
}
print "$kind ok\n";
exit 0;
VERIFY
}

sub write_executable {
    my ($path, $bytes) = @_;
    write_file($path, $bytes);
    chmod 0755, $path or die "chmod $path: $!";
    return $path;
}
sub write_file {
    my ($path, $bytes) = @_;
    open my $fh, '>:raw', $path or die "write $path: $!";
    print {$fh} $bytes;
    close $fh or die "close $path: $!";
    return $path;
}
sub read_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "read $path: $!";
    my $bytes = do { local $/; <$fh> };
    close $fh or die "close $path: $!";
    return $bytes;
}
sub load_json { return JSON::PP->new->decode(read_file($_[0])) }
sub entries {
    my ($dir) = @_;
    opendir my $fh, $dir or die $!;
    my @entries = sort grep { $_ ne '.' && $_ ne '..' } readdir $fh;
    closedir $fh;
    return @entries;
}
