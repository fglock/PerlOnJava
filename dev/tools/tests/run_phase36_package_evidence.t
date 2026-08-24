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
    ok($evidence->{verified}, 'A235-compatible verified field is emitted');
    is($evidence->{missing_entries}, 0, 'A235-compatible missing count is zero');
    is($evidence->{duplicate_entries}, 0, 'A235-compatible duplicate count is zero');
    is($evidence->{jar_sha256}, $evidence->{identity}{jar_sha256},
        'A235-compatible JAR identity is emitted');
    is($evidence->{sbom_sha256}, $evidence->{identity}{sbom_sha256},
        'A235-compatible SBOM identity is emitted');
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
    ok(grep($_ eq 'jar-version', @names), 'trusted Java executes the JAR version path');
    ok(grep($_ eq 'jar-commit-resolve', @names), 'JAR commit is resolved to a full SHA');
    is($evidence->{package}{package}, 'perlonjava', 'exact package name is retained');
    is($evidence->{package}{version}, '5.44.0', 'exact package version is retained');
    is($evidence->{package}{architecture}, 'all', 'exact package architecture is retained');
    ok($evidence->{notice_license_artifact}{verified},
        'durable notice/license record is retained');
    ok(!grep(!/\A[0-9a-f]{64}\z/, map { $_->{argv_sha256} } @{$evidence->{commands}}),
        'every complete argv is hashed');
    ok(!grep(!/\A[0-9a-f]{64}\z/, map { $_->{log_sha256} } @{$evidence->{commands}}),
        'every command log is hashed');
};

for my $case (
    ['tamper', 'tamper', qr/dirty or mutated/],
    ['path escape', 'path', qr/unexpected symlink/],
    ['timeout', 'timeout', qr/timed out/],
    ['malformed package', 'malformed', qr/dpkg-control exited nonzero/],
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

for my $case (
    ['JAR full-commit binding', 'jar-wrong-commit', qr/jar-commit-resolve exited nonzero/],
    ['configured package name', 'config-package', qr/exactly perlonjava\/5\.44\.0/],
    ['configured package version', 'config-version', qr/exactly perlonjava\/5\.44\.0/],
    ['configured architecture', 'config-architecture', qr/unsupported architecture/],
    ['Make package target', 'make-contract', qr/Makefile does not expose/],
    ['configured maintainer', 'config-maintainer', qr/control Maintainer mismatch/],
    ['control package', 'control-package', qr/control Package mismatch/],
    ['control version', 'control-version', qr/control Version mismatch/],
    ['control architecture', 'control-architecture', qr/control Architecture mismatch/],
    ['control maintainer', 'control-maintainer', qr/control Maintainer mismatch/],
    ['duplicate control field', 'control-duplicate', qr/duplicate field Package/],
    ['installDist exact name', 'install-name', qr/installDist parent has unexpected names/],
    ['standalone exact name', 'jar-name', qr/exact standalone JAR/],
    ['Debian exact name', 'deb-name', qr/package distributions has unexpected names/],
    ['payload missing entry', 'payload-missing', qr/missing installDist entry/],
    ['payload differing bytes', 'payload-different', qr/differs from installDist/],
    ['payload extra entry', 'payload-extra', qr/unexpected entries/],
    ['payload case-fold collision', 'listing-case-collision', qr/case-fold collision/],
    ['payload duplicate entry', 'listing-duplicate', qr/duplicate path/],
    ['required symlink missing', 'symlink-missing', qr/payload missing entries/],
    ['required symlink target', 'symlink-wrong', qr/unexpected symlink/],
    ['unsafe package listing', 'listing-escape', qr/unsafe path/],
    ['immutable data mutation', 'input-mutation', qr/Protected tool\/config (?:identity changed|mutated)/],
    ['verifier mutation', 'verifier-mutation', qr/Protected tool\/config (?:identity changed|mutated)/],
    ['artifact mutation', 'artifact-mutation', qr/Generated package artifact (?:identity changed|mutated)/],
    ['launcher replacement', 'launcher-replacement', qr/Protected tool\/config identity changed/],
    ['trusted tool mutation', 'tool-mutation', qr/Trusted java executable (?:identity changed|mutated)/],
    ['output-root mutation', 'output-mutation', qr/Sealed output root changed/],
) {
    subtest "$case->[0] is rejected without publication" => sub {
        my $fixture = fixture($case->[1]);
        my ($status, $text) = run_producer($fixture, 3);
        isnt($status, 0, 'producer rejects adversarial fixture');
        like($text, $case->[2], 'diagnostic identifies the violated contract');
        is_deeply([grep { $_ eq 'package-evidence.json' } entries($fixture->{output})], [],
            'no evidence artifact is published');
    };
}

subtest 'every stale SBOM and package-output spelling is rejected pre-build' => sub {
    my @stale = (
        ['build', 'reports', 'bom.json'],
        ['build', 'reports', 'perl-bom.json'],
        ['build', 'reports', 'sbom.json'],
        ['build', 'reports', 'nested', 'BOM.JSON'],
        ['build', 'reports', 'nested', 'perl-bom.json'],
        ['build', 'reports', 'nested', 'sbom.json'],
        ['target', 'perlonjava-5.44.0.jar'],
        ['build', 'other', 'stale.deb'],
    );
    for my $parts (@stale) {
        my $fixture = fixture('stale-matrix');
        my $path = File::Spec->catfile($fixture->{source}, @$parts);
        my @parent = @$parts; pop @parent;
        make_path(File::Spec->catdir($fixture->{source}, @parent));
        write_file($path, "stale\n");
        my ($status, $text) = run_producer($fixture, 2);
        isnt($status, 0, join('/', @$parts) . ' is rejected');
        like($text, qr/Stale preexisting package output/, 'stale diagnostic is explicit');
        ok(!-e File::Spec->catfile($fixture->{source}, 'MAKE-RAN'),
            'bounded build never starts');
    }
};

subtest 'exclusive atomic publication loses an adversarial destination race safely' => sub {
    my $fixture = fixture('publication-race');
    my ($status, $text) = run_producer($fixture, 5);
    isnt($status, 0, 'producer refuses to overwrite a racing destination');
    like($text, qr/(?:exclusively atomically publish|output root changed)/,
        'publication race is diagnosed');
    my $final = File::Spec->catfile($fixture->{output}, 'package-evidence.json');
    is(read_file($final), "racer\n", 'racing bytes are never overwritten');
};

for my $case (
    ['on-write log bytes', 'log-overflow', [qw(--max-log-bytes 256)], qr/log byte limit/],
    ['bounded input JSON', 'json-overflow', [qw(--max-json-bytes 2048)], qr/merged SBOM exceeds byte limit/],
    ['bounded artifact bytes', 'artifact-overflow', [qw(--max-artifact-bytes 4096)], qr/artifact byte limit/],
    ['bounded tree bytes', 'happy', [qw(--max-tree-bytes 256)], qr/tree byte limit/],
    ['bounded tree entries', 'happy', [qw(--max-tree-entries 5)], qr/tree entry limit/],
    ['bounded tree depth', 'happy', [qw(--max-tree-depth 3)], qr/tree depth limit/],
    ['bounded evidence JSON', 'happy', [qw(--max-json-bytes 2048)], qr/Evidence JSON exceeds byte limit/],
) {
    subtest "$case->[0] fails closed" => sub {
        my $fixture = fixture($case->[1]);
        my ($status, $text) = run_producer($fixture, 3, @{$case->[2]});
        isnt($status, 0, 'producer enforces configured lower bound');
        like($text, $case->[3], 'bounded diagnostic is explicit');
        is_deeply([entries($fixture->{output})], [], 'bounded failure publishes nothing');
    };
}

done_testing;

sub run_producer {
    my ($fixture, $timeout, @extra) = @_;
    my $log = File::Spec->catfile($fixture->{base}, 'producer.log');
    my @argv = ($system_perl, $producer,
        '--source-root', $fixture->{source}, '--expected-commit', $commit,
        '--output-root', $fixture->{output}, '--make', $fixture->{tools}{make},
        '--perl', $system_perl, '--git', $fixture->{tools}{git},
        '--dpkg-deb', $fixture->{tools}{dpkg}, '--java', $fixture->{tools}{java},
        '--jar', $fixture->{tools}{jar},
        '--timeout', $timeout, @extra);
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
        (map { File::Spec->catdir($tools, $_) } qw(make-bin git-bin dpkg-bin java-bin)),
        File::Spec->catdir($source, 'dev', 'tools'),
        File::Spec->catdir($source, 'gradle', 'wrapper'),
        File::Spec->catdir($source, 'src', 'main', 'java', 'org', 'perlonjava', 'core'),
        File::Spec->catdir($source, 'third_party', 'joni'),
        File::Spec->catdir($source, 'third_party', 'licenses'));
    write_file(File::Spec->catfile($source, 'SCENARIO'), "$scenario\n");
    write_file(File::Spec->catfile($source, 'EXPECTED_COMMIT'), "$commit\n");
    write_file(File::Spec->catfile($source, 'OUTPUT_ROOT'), "$output\n");
    write_executable(File::Spec->catfile($source, 'jperl'), "#!/bin/sh\nexit 0\n");
    write_file(File::Spec->catfile($source, 'src', 'main', 'java', 'org',
        'perlonjava', 'core', 'Configuration.java.in'), "gitCommitId = \"dev\";\n");
    my $makefile = "deb: check-java-gradle\nifeq (\$(OS),Windows_NT)\n"
        . "\tgradlew.bat buildDeb\nelse\n\t./gradlew buildDeb\nendif\n";
    $makefile =~ s/buildDeb/wrongTask/g if $scenario eq 'make-contract';
    write_file(File::Spec->catfile($source, 'Makefile'), $makefile);
    write_file(File::Spec->catfile($source, 'build.gradle'), <<'GRADLE');
version = '5.44.0'
ospackage {
    packageName = 'perlonjava'
    version = project.version
    maintainer = 'Flavio Soibelmann Glock <fglock@gmail.com>'
}
GRADLE
    if ($scenario =~ /\Aconfig-(?:package|version|maintainer|architecture)\z/) {
        my $path = File::Spec->catfile($source, 'build.gradle');
        my $text = read_file($path);
        $text =~ s/packageName = 'perlonjava'/packageName = 'other'/
            if $scenario eq 'config-package';
        $text =~ s/version = '5\.44\.0'/version = '5.44.1'/
            if $scenario eq 'config-version';
        $text =~ s/maintainer = '[^']+'/maintainer = 'Other <other\@example.com>'/
            if $scenario eq 'config-maintainer';
        $text .= "architecture = X86_64\n" if $scenario eq 'config-architecture';
        write_file($path, $text);
    }
    write_file(File::Spec->catfile($source, 'settings.gradle'), "rootProject.name='x'\n");
    write_file(File::Spec->catfile($source, 'gradle', 'wrapper',
        'gradle-wrapper.properties'), "distributionUrl=fake\n");
    write_file(File::Spec->catfile($source, 'third_party', 'joni', 'LICENSE'), "joni license\n");
    write_file(File::Spec->catfile($source, 'third_party', 'joni',
        'PERLONJAVA-NOTICE.md'), "joni notice\n");
    write_file(File::Spec->catfile($source, 'third_party', 'licenses',
        'jcodings-LICENSE.txt'), "jcodings license\n");

    my $make = write_executable(File::Spec->catfile($tools, 'make-bin', 'make'), <<'MAKE');
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
print "L" x 4096 if $scenario eq 'log-overflow';
my $install_name = $scenario eq 'install-name' ? 'PerlOnJava' : 'perlonjava';
my $install = "$root/build/install/$install_name";
my $package = "$root/.fake-package-tree/opt/perlonjava";
for my $dir ($install, $package) {
    make_path("$dir/lib", "$dir/bin", "$dir/share/licenses", "$dir/share/sbom");
}
make_path("$root/target", "$root/build/reports", "$root/build/distributions");
make_path("$root/.fake-package-tree/usr/local/bin");
open my $cf, '<', "$root/EXPECTED_COMMIT" or die $!; chomp(my $commit = <$cf>); close $cf;
my $jar = "JAR:$commit\n";
$jar .= 'x' x 5000 if $scenario eq 'artifact-overflow';
my $sbom = JSON::PP->new->canonical->encode({ bomFormat => 'CycloneDX', components => [{
    group => 'org.perlonjava.fork', name => 'joni-fork', properties => [{
        name => 'perlonjava:source-commit', value => $commit }]}],
    ($scenario eq 'json-overflow' ? (padding => 'x' x 4096) : ()) });
sub put { my ($path, $bytes) = @_; open my $fh, '>:raw', $path or die $!;
    print {$fh} $bytes; close $fh or die $! }
my $jar_name = $scenario eq 'jar-name' ? 'perlonjava-5.44.jar'
    : 'perlonjava-5.44.0.jar';
put("$root/target/$jar_name", $jar);
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
put("$package/bin/perlonjava", "different\n") if $scenario eq 'payload-different';
unlink "$package/bin/perlonjava.bat" if $scenario eq 'payload-missing';
put("$package/EXTRA", "extra\n") if $scenario eq 'payload-extra';
make_path("$package/Lib") if $scenario eq 'case-collision';
symlink('../../outside', "$install/lib/escape") if $scenario eq 'path';
for my $name (qw(jperl jcpan jperldoc jprove)) {
    next if $scenario eq 'symlink-missing' && $name eq 'jprove';
    my $target = $scenario eq 'symlink-wrong' && $name eq 'jperl'
        ? '/opt/perlonjava/bin/jcpan' : "/opt/perlonjava/bin/$name";
    symlink($target, "$root/.fake-package-tree/usr/local/bin/$name")
        or die $!;
}
my $deb_name = $scenario eq 'deb-name' ? 'PerlOnJava_5.44.0_all.deb'
    : 'perlonjava_5.44.0_all.deb';
put("$root/build/distributions/$deb_name", "DEB\n");
if ($scenario eq 'tool-mutation') {
    open my $fh, '>>', "$root/../tools/java-bin/java" or die $!;
    print {$fh} "# changed\n"; close $fh;
}
if ($scenario eq 'launcher-replacement') {
    my $path = "$root/jperl"; my $tmp = "$root/jperl.new";
    open my $in, '<:raw', $path or die $!; local $/; my $bytes = <$in>; close $in;
    put($tmp, $bytes); chmod 0755, $tmp; rename($tmp, $path) or die $!;
}
if ($scenario eq 'output-mutation') {
    open my $of, '<', "$root/OUTPUT_ROOT" or die $!; chomp(my $output = <$of>);
    put("$output/foreign", "foreign\n");
}
MAKE

    my $git = write_executable(File::Spec->catfile($tools, 'git-bin', 'git'), <<'GIT');
#!/usr/bin/perl
use strict; use warnings;
my $root;
if (@ARGV >= 2 && $ARGV[0] eq '-C') { $root = $ARGV[1]; splice @ARGV, 0, 2 }
if (@ARGV >= 2 && $ARGV[0] eq 'rev-parse' && $ARGV[1] eq 'HEAD') {
    open my $fh, '<', "$root/EXPECTED_COMMIT" or die $!; print while <$fh>; exit 0;
}
if (@ARGV >= 3 && $ARGV[0] eq 'rev-parse' && $ARGV[1] eq '--verify') {
    open my $fh, '<', "$root/EXPECTED_COMMIT" or die $!; chomp(my $expected = <$fh>);
    my ($prefix) = $ARGV[2] =~ /\A([0-9a-f]+)\^\{commit\}\z/;
    exit 8 unless defined $prefix && index($expected, $prefix) == 0;
    print "$expected\n"; exit 0;
}
if (@ARGV && $ARGV[0] eq 'status') { print " M protected\n" if -e "$root/DIRTY"; exit 0 }
die "unexpected fake git argv: @ARGV";
GIT

    my $dpkg = write_executable(File::Spec->catfile($tools, 'dpkg-bin', 'dpkg-deb'), <<'DPKG');
#!/usr/bin/perl
use strict; use warnings;
use File::Find qw(find);
use File::Path qw(make_path);
use File::Basename qw(dirname);
my ($mode, $deb, $destination) = @ARGV;
my $root = dirname(dirname(dirname($deb)));
open my $sf, '<', "$root/SCENARIO" or die $!; chomp(my $scenario = <$sf>); close $sf;
exit 9 if $mode eq '--field' && $scenario eq 'malformed';
if ($mode eq '--field') {
    my $package = $scenario eq 'control-package' ? 'other' : 'perlonjava';
    my $version = $scenario eq 'control-version' ? '5.44.1' : '5.44.0';
    my $architecture = $scenario eq 'control-architecture' ? 'amd64' : 'all';
    my $maintainer = $scenario eq 'control-maintainer' ? 'Other <other@example.com>'
        : 'Flavio Soibelmann Glock <fglock@gmail.com>';
    print "Package: $package\nVersion: $version\nArchitecture: $architecture\n";
    print "Maintainer: $maintainer\n";
    print "Package: duplicate\n" if $scenario eq 'control-duplicate';
    exit 0;
}
if ($mode eq '--contents') {
    if ($scenario eq 'listing-escape') {
        print "-rw-r--r-- root/root 4 2026-01-01 00:00 ./../escape\n"; exit 0;
    }
    if ($scenario eq 'listing-case-collision') {
        print "-rw-r--r-- root/root 4 2026-01-01 00:00 ./opt/perlonjava/lib/X\n";
        print "-rw-r--r-- root/root 4 2026-01-01 00:00 ./opt/perlonjava/lib/x\n";
        exit 0;
    }
    if ($scenario eq 'listing-duplicate') {
        print "-rw-r--r-- root/root 4 2026-01-01 00:00 ./opt/perlonjava/lib/x\n" x 2;
        exit 0;
    }
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
    if (-l $_) { symlink(readlink($_), $to) or die $!; return }
    open my $in, '<:raw', $_ or die $!; open my $out, '>:raw', $to or die $!;
    local $/; print {$out} <$in>; close $in; close $out;
}}, $tree);
exit 0;
DPKG
    my $jar = write_executable(File::Spec->catfile($tools, 'java-bin', 'jar'), <<'JAR');
#!/usr/bin/perl
exit 0;
JAR
    my $java = write_executable(File::Spec->catfile($tools, 'java-bin', 'java'), <<'JAVA');
#!/usr/bin/perl
use strict; use warnings;
my $jar = $ARGV[1];
my $root = $jar; $root =~ s{/target/.*\z}{};
open my $fh, '<', "$root/EXPECTED_COMMIT" or die $!;
chomp(my $commit = <$fh>);
open my $sf, '<', "$root/SCENARIO" or die $!; chomp(my $scenario = <$sf>);
$commit = 'b' x 40 if $scenario eq 'jar-wrong-commit';
print "git_commit_id='" . substr($commit, 0, 12) . "';\n";
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
use FindBin; use Digest::SHA qw(sha256_hex); use JSON::PP; use Fcntl qw(:DEFAULT);
use File::Basename qw(dirname);
my \$root = "\$FindBin::Bin/../..";
open my \$sf, '<', "\$root/SCENARIO" or die \$!; chomp(my \$scenario = <\$sf>); close \$sf;
if ('$kind' eq 'packaging' && \$scenario eq 'tamper') {
    open my \$fh, '>', "\$root/DIRTY" or die \$!; print {\$fh} "dirty\n"; close \$fh;
}
if ('$kind' eq 'packaging' && \$scenario eq 'input-mutation') {
    open my \$fh, '>>', "\$root/third_party/joni/LICENSE" or die \$!;
    print {\$fh} "changed\n"; close \$fh;
}
if ('$kind' eq 'packaging' && \$scenario eq 'verifier-mutation') {
    open my \$fh, '>>', __FILE__ or die \$!; print {\$fh} "# changed\n"; close \$fh;
}
if ('$kind' eq 'packaging' && \$scenario eq 'artifact-mutation') {
    my \$jar = \$ARGV[-2]; open my \$fh, '>>', \$jar or die \$!;
    print {\$fh} "changed\n"; close \$fh;
}
exit 7 if '$kind' eq 'notice' && \$scenario eq 'verifier-fail';
if ('$kind' eq 'notice') {
    my %o; while (\@ARGV) { my \$key = shift \@ARGV; next if \$key eq '--strict'; \$key =~ s/^--//; \$o{\$key} = shift \@ARGV }
    sub bytes { open my \$fh, '<:raw', \$_[0] or die \$!; local \$/; return <\$fh> }
    open my \$out, '>:raw', \$o{output} or die \$!;
    print {\$out} JSON::PP->new->canonical->encode({ verified => JSON::PP::true,
        jar_sha256 => sha256_hex(bytes(\$o{jar})), sbom_sha256 => sha256_hex(bytes(\$o{sbom})),
        (\$scenario eq 'publication-race' ? (padding => 'x' x 5_000_000) : ()) });
    close \$out;
    if (\$scenario eq 'publication-race') {
        my \$pid = fork(); die "fork: \$!" unless defined \$pid;
        if (!\$pid) {
            open STDIN, '<', '/dev/null'; open STDOUT, '>', '/dev/null';
            open STDERR, '>', '/dev/null';
            open my \$of, '<', "\$root/OUTPUT_ROOT" or exit 2;
            chomp(my \$output = <\$of>); close \$of;
            my \$parent = dirname(\$output);
            for (1 .. 10_000) {
                opendir my \$dh, \$parent or exit 3;
                my \$seen = grep { /\\A\\.package-evidence\\./ } readdir \$dh;
                closedir \$dh;
                if (\$seen) {
                    my \$final = "\$output/package-evidence.json";
                    if (sysopen(my \$race, \$final, O_WRONLY | O_CREAT | O_EXCL, 0600)) {
                        print {\$race} "racer\n"; close \$race;
                    }
                    exit 0;
                }
                select undef, undef, undef, 0.001;
            }
            exit 4;
        }
    }
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
