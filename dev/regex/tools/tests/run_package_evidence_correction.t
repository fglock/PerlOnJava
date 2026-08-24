#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path);
use Config;
use Digest::SHA qw(sha256_hex);
use File::Basename qw(dirname);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin qw($Bin);
use JSON::PP;
use Test::More;

my $producer = abs_path(File::Spec->catfile($Bin, '..',
    'run_package_evidence.pl'));
my $system_perl = abs_path($Config{perlpath});
my $temporary = tempdir('regex_implementation-package-correction-XXXXXX', TMPDIR => 1,
    CLEANUP => 1);
my $commit = 'a' x 40;

subtest 'exact acceptance bridge retains only hashed durable artifacts' => sub {
    my $fixture = fixture('happy');
    my ($status, $text) = run_producer($fixture);
    diag($text) if $status;
    is($status, 0, 'producer succeeds');
    my $bridge_path = File::Spec->catfile($fixture->{output},
        'package-evidence.json');
    my $bridge = load_json($bridge_path);
    is_deeply([sort keys %$bridge], [sort qw(schema_version kind producer verified
        identity completion artifacts missing_entries duplicate_entries)],
        'bridge has the exact locked top-level schema');
    is($bridge->{kind}, 'packaging', 'bridge kind is packaging');
    is($bridge->{producer}, 'run_package_evidence.pl',
        'producer literal is exact and inert');
    ok($bridge->{verified}, 'bridge is verified');
    my $retained_sbom = File::Spec->catfile($fixture->{output}, 'package',
        'sbom.json');
    is_deeply($bridge->{identity}, {
        source_commit => $commit,
        jar_sha256 => sha256_hex("JAR:$commit\n"),
        sbom_sha256 => sha256_file($retained_sbom),
    }, 'bridge identity is exact');
    is_deeply($bridge->{completion}, {
        exit_code => 0, signal => 0, timeout => JSON::PP::false,
        incomplete => JSON::PP::false, review_stop => JSON::PP::false,
    }, 'completion tuple is clean and exact');
    my @descriptors;
    collect_descriptors($bridge->{artifacts}, \@descriptors);
    ok(@descriptors > 8, 'bridge retains rich evidence through descriptor leaves');
    for my $descriptor (@descriptors) {
        is_deeply([sort keys %$descriptor], [qw(path sha256 size)],
            'descriptor schema is exact');
        my $path = File::Spec->catfile($fixture->{output},
            split m{/}, $descriptor->{path});
        ok(-f $path && !-l $path, 'descriptor resolves to a regular retained file');
        is(sha256_file($path), $descriptor->{sha256}, 'descriptor hash matches');
        is(-s $path, $descriptor->{size}, 'descriptor size matches');
    }
    my $report = load_json(File::Spec->catfile($fixture->{output}, 'package',
        'package-evidence-report.json'));
    is($report->{kind}, 'regex_implementation-package-evidence-report',
        'rich report is distinct from the acceptance bridge');
    is($report->{sbom_relation}{java_bom_sha256},
        sha256_file(File::Spec->catfile($fixture->{output}, 'package', 'bom.json')),
        'Java BOM is independently retained and bound');
    is($report->{sbom_relation}{perl_bom_sha256},
        sha256_file(File::Spec->catfile($fixture->{output}, 'package', 'perl-bom.json')),
        'Perl BOM is independently retained and bound');
};

subtest 'both component BOMs absent emits only legacy non-authoritative report' => sub {
    my $fixture = fixture('legacy-both-missing');
    my ($status, $text) = run_producer($fixture);
    is($status, 0, 'legacy generated artifact set remains compatible') or diag $text;
    is_deeply([entries($fixture->{output})], ['package-evidence.json'],
        'legacy path publishes one rich report');
    my $report = load_json(File::Spec->catfile($fixture->{output},
        'package-evidence.json'));
    is($report->{kind}, 'regex_implementation-package-evidence-report',
        'legacy kind cannot satisfy packaging bridge kind');
    ok(!$report->{authoritative}, 'legacy report is explicitly non-authoritative');
    ok(!exists($report->{completion}), 'legacy report has no strict completion tuple');
    isnt(join(',', sort keys %$report), join(',', sort qw(schema_version kind
        producer verified identity completion artifacts missing_entries
        duplicate_entries)), 'legacy report cannot satisfy exact bridge schema');
    is($report->{sbom_relation}{relation}, 'legacy-merged-sbom-only',
        'legacy report records its non-authoritative SBOM relation');
};

subtest 'artifact state alone selects strict contract' => sub {
    my $fixture = fixture('strict-env-downgrade');
    my ($status, $text) = run_producer($fixture, '--mode', 'report');
    is($status, 0, 'strict artifacts ignore environment and report-mode downgrade attempts')
        or diag $text;
    my $bridge = load_json(File::Spec->catfile($fixture->{output},
        'package-evidence.json'));
    is($bridge->{kind}, 'packaging', 'both component BOMs force strict bridge');
    ok(exists($bridge->{completion}), 'strict completion remains present');

    my $evidence = fixture('legacy-evidence-strict');
    my ($evidence_status, $evidence_text) = run_producer($evidence);
    isnt($evidence_status, 0, 'legacy SBOM field cannot select strict contract');
    like($evidence_text, qr/legacy merged SBOM fields differ/,
        'evidence selector field is rejected by legacy schema');
    is_deeply([entries($evidence->{output})], [],
        'evidence selection attempt publishes nothing');
};

for my $case (
    ['missing Java BOM', 'missing-java-bom', qr/Generated component BOM set is incomplete/],
    ['missing Perl BOM', 'missing-perl-bom', qr/Generated component BOM set is incomplete/],
    ['wrong merged relation', 'bad-relation', qr/component relation/],
    ['duplicate merged key', 'duplicate-sbom-key', qr/duplicate object key/],
    ['unexpected merged field', 'extra-sbom-field', qr/fields differ from the locked schema/],
    ['unexpected notice field', 'extra-notice-field', qr/notice\/license verifier output fields differ/],
    ['post-link source mutation', 'post-link-source-mutation', qr/(?:Protected tool\/config|post-publication)/],
) {
    subtest "$case->[0] fails assertion mode closed" => sub {
        my $fixture = fixture($case->[1]);
        my ($status, $text) = run_producer($fixture);
        isnt($status, 0, 'producer rejects the reducer');
        like($text, $case->[2], 'failure identifies the rejected boundary');
        is_deeply([entries($fixture->{output})], [],
            'assertion failure publishes no bridge, report, or nested artifact');
    };
}

subtest 'report mode publishes only a bounded non-acceptable failure record' => sub {
    my $fixture = fixture('missing-java-bom');
    my ($status, $text) = run_producer($fixture, '--mode', 'report');
    isnt($status, 0, 'report mode retains nonzero failure status');
    is_deeply([entries($fixture->{output})], ['package-evidence-failure.json'],
        'only the distinctly named failure record is published');
    my $failure = load_json(File::Spec->catfile($fixture->{output},
        'package-evidence-failure.json'));
    is($failure->{kind}, 'regex_implementation-package-evidence-failure',
        'failure kind cannot satisfy the acceptance bridge');
    ok(!$failure->{verified}, 'failure record is explicitly unverified');
    ok($failure->{completion}{incomplete}, 'failure record is incomplete');
    cmp_ok(-s File::Spec->catfile($fixture->{output},
        'package-evidence-failure.json'), '<=', 65_536, 'failure record is bounded');
};

subtest 'report publication rejects same-user post-link mutation' => sub {
    my $fixture = fixture('report-final-mutation');
    my ($status, $text) = run_producer($fixture, '--mode', 'report');
    isnt($status, 0, 'mutated report failure remains nonzero');
    like($text, qr/(?:Failure notice staging source|Published failure notice).*(?:identity changed|mutated)/,
        'pre-link seal detects the same-inode mutation');
    is_deeply([entries($fixture->{output})], [],
        'mutated failure record is safely removed');
};

subtest 'assertion staging unlink failure removes all success publication' => sub {
    my $fixture = fixture('success-staging-unlink');
    my ($status, $text) = run_producer($fixture);
    isnt($status, 0, 'injected staging unlink failure is nonzero');
    like($text, qr/Injected success staging unlink failure/,
        'fault reaches the post-link staging unlink boundary');
    is_deeply([entries($fixture->{output})], [],
        'success bridge and nested bundle are removed before failure returns');
};

my %maximum = (
    timeout => 86_400, 'max-log-bytes' => 1_048_576,
    'max-json-bytes' => 8_388_608, 'max-artifact-bytes' => 536_870_912,
    'max-tree-bytes' => 1_073_741_824, 'max-tree-entries' => 100_000,
    'max-tree-depth' => 64,
);
for my $name (sort keys %maximum) {
    for my $value ('0', '+1', '01', '1e2', ($maximum{$name} + 1), '9' x 80) {
        subtest "lexical bound rejects --$name=$value" => sub {
            my $fixture = fixture('option-reducer');
            my ($status, $text) = run_producer($fixture, '--mode', 'report',
                "--$name", $value);
            isnt($status, 0, 'malformed or overflowing decimal is rejected');
            like($text, qr/canonical positive decimal string/, 'lexical diagnostic is explicit');
            is_deeply([entries($fixture->{output})],
                ['package-evidence-failure.json'], 'bounded report failure is durable');
            ok(!-e File::Spec->catfile($fixture->{source}, 'MAKE-RAN'),
                'numeric reducer is rejected before conversion or build');
        };
    }
}

done_testing;

sub run_producer {
    my ($fixture, @extra) = @_;
    my $log = File::Spec->catfile($fixture->{base}, 'producer.log');
    my @argv = ($system_perl, $producer,
        '--source-root', $fixture->{source}, '--expected-commit', $commit,
        '--output-root', $fixture->{output}, '--make', $fixture->{tools}{make},
        '--perl', $system_perl, '--git', $fixture->{tools}{git},
        '--dpkg-deb', $fixture->{tools}{dpkg}, '--java', $fixture->{tools}{java},
        '--jar', $fixture->{tools}{jar}, '--timeout', '3', @extra);
    my $pid = fork(); die "fork failed: $!" unless defined $pid;
    if (!$pid) {
        open STDOUT, '>:raw', $log or die $!;
        open STDERR, '>&', \*STDOUT or die $!;
        if ($fixture->{scenario} eq 'report-final-mutation') {
            $ENV{HARNESS_ACTIVE} = 1;
            $ENV{PERLONJAVA_REGEX_IMPLEMENTATION_TEST_FAULT} = 'report-final-mutation';
        } elsif ($fixture->{scenario} eq 'success-staging-unlink') {
            $ENV{HARNESS_ACTIVE} = 1;
            $ENV{PERLONJAVA_REGEX_IMPLEMENTATION_TEST_FAULT} = 'success-staging-unlink';
        } elsif ($fixture->{scenario} eq 'strict-env-downgrade') {
            $ENV{PERLONJAVA_REGEX_IMPLEMENTATION_PACKAGE_CONTRACT} = 'legacy';
            $ENV{PERLONJAVA_REGEX_IMPLEMENTATION_AUTHORITATIVE} = 0;
        }
        exec { $argv[0] } @argv; die $!;
    }
    waitpid($pid, 0);
    return ($? >> 8, read_file($log));
}

sub fixture {
    my ($scenario) = @_;
    my $base = File::Spec->catdir($temporary,
        "$scenario-" . int(rand(1_000_000_000)));
    my $source = File::Spec->catdir($base, 'source');
    my $output = File::Spec->catdir($base, 'output');
    my $tools = File::Spec->catdir($base, 'tools');
    make_path($source, $output, $tools,
        map { File::Spec->catdir($tools, $_) } qw(make-bin git-bin dpkg-bin java-bin));
    make_path(File::Spec->catdir($source, 'dev', 'regex', 'tools'),
        File::Spec->catdir($source, 'gradle', 'wrapper'),
        File::Spec->catdir($source, 'src', 'main', 'java', 'org', 'perlonjava', 'core'),
        File::Spec->catdir($source, 'third_party', 'joni'),
        File::Spec->catdir($source, 'third_party', 'licenses'));
    write_file(File::Spec->catfile($source, 'SCENARIO'), "$scenario\n");
    write_file(File::Spec->catfile($source, 'EXPECTED_COMMIT'), "$commit\n");
    write_file(File::Spec->catfile($source, 'OUTPUT_ROOT'), "$output\n");
    write_executable(File::Spec->catfile($source, 'jperl'), "#!/bin/sh\nexit 0\n");
    write_file(File::Spec->catfile($source, 'Makefile'),
        "deb: check-java-gradle\nifeq (\$(OS),Windows_NT)\n\tgradlew.bat buildDeb\nelse\n\t./gradlew buildDeb\nendif\n");
    write_file(File::Spec->catfile($source, 'build.gradle'), <<'GRADLE');
version = '5.44.0'
ospackage {
    packageName = 'perlonjava'
    version = project.version
    maintainer = 'Flavio Soibelmann Glock <fglock@gmail.com>'
}
GRADLE
    write_file(File::Spec->catfile($source, 'settings.gradle'), "rootProject.name='x'\n");
    write_file(File::Spec->catfile($source, 'gradle', 'wrapper',
        'gradle-wrapper.properties'), "distributionUrl=fake\n");
    write_file(File::Spec->catfile($source, 'src', 'main', 'java', 'org',
        'perlonjava', 'core', 'Configuration.java.in'), "gitCommitId = \"dev\";\n");
    write_file(File::Spec->catfile($source, 'third_party', 'joni', 'LICENSE'), "license\n");
    write_file(File::Spec->catfile($source, 'third_party', 'joni',
        'PERLONJAVA-NOTICE.md'), "notice\n");
    write_file(File::Spec->catfile($source, 'third_party', 'licenses',
        'jcodings-LICENSE.txt'), "jcodings\n");

    my $make = write_executable(File::Spec->catfile($tools, 'make-bin', 'make'), <<'MAKE');
#!/usr/bin/perl
use strict; use warnings; use File::Path qw(make_path); use JSON::PP;
my $root; for (my $i=0; $i<@ARGV; $i++) { $root=$ARGV[$i+1] if $ARGV[$i] eq '-C' }
open my $sf,'<',"$root/SCENARIO" or die $!; chomp(my $scenario=<$sf>); close $sf;
open my $cf,'<',"$root/EXPECTED_COMMIT" or die $!; chomp(my $commit=<$cf>); close $cf;
open my $ran,'>',"$root/MAKE-RAN" or die $!; print {$ran} "yes\n"; close $ran;
my $install="$root/build/install/perlonjava"; my $package="$root/.package/opt/perlonjava";
for my $dir ($install,$package) { make_path("$dir/bin","$dir/lib","$dir/share/licenses","$dir/share/sbom") }
make_path("$root/target","$root/build/reports","$root/build/distributions","$root/.package/usr/local/bin");
sub put { my($p,$b)=@_; open my $f,'>:raw',$p or die $!; print {$f} $b; close $f or die $! }
my $jar="JAR:$commit\n";
my $jcodings={type=>'library','bom-ref'=>'jcodings',group=>'org.jruby.jcodings',name=>'jcodings'};
my $perlcomp={type=>'library','bom-ref'=>'perl:strict',name=>'strict'};
my $joni={type=>'library','bom-ref'=>'pkg:generic/perlonjava/joni-fork@2.2.7',
 group=>'org.perlonjava.fork',name=>'joni-fork',properties=>[
 {name=>'perlonjava:source-commit',value=>$commit}]};
my $base={ '$schema'=>'http://cyclonedx.org/schema/bom-1.6.schema.json',bomFormat=>'CycloneDX',
 specVersion=>'1.6',serialNumber=>'urn:uuid:test',version=>1,metadata=>{},dependencies=>[]};
my $json=JSON::PP->new->canonical;
my $java=$json->encode({%$base,components=>[$jcodings]});
my $perl=$json->encode({%$base,components=>[$perlcomp]});
my $merged=$json->encode({%$base,components=>[$jcodings,$joni,$perlcomp],dependencies=>[
 {ref=>'perlonjava',dependsOn=>['jcodings','pkg:generic/perlonjava/joni-fork@2.2.7','perl:strict']},
 {ref=>'pkg:generic/perlonjava/joni-fork@2.2.7',dependsOn=>['pkg:maven/org.jruby.jcodings/jcodings@1.0.64?type=jar']}]});
if ($scenario eq 'legacy-both-missing' || $scenario eq 'legacy-evidence-strict') {
 $merged=$json->encode({bomFormat=>'CycloneDX',components=>[$joni]});
 if ($scenario eq 'legacy-evidence-strict') { my $d=$json->decode($merged); $d->{package_contract}='strict'; $merged=$json->encode($d) }
}
$merged =~ s/\A\{/\{"bomFormat":"CycloneDX",/ if $scenario eq 'duplicate-sbom-key';
if ($scenario eq 'extra-sbom-field') { my $d=$json->decode($merged); $d->{unexpected}=1; $merged=$json->encode($d) }
if ($scenario eq 'bad-relation') { my $d=$json->decode($merged); pop @{$d->{components}}; $merged=$json->encode($d) }
put("$root/target/perlonjava-5.44.0.jar",$jar);
put("$root/build/reports/bom.json",$java)
 unless $scenario eq 'missing-java-bom' || $scenario eq 'report-final-mutation'
    || $scenario eq 'legacy-both-missing' || $scenario eq 'legacy-evidence-strict';
put("$root/build/reports/perl-bom.json",$perl)
 unless $scenario eq 'missing-perl-bom' || $scenario eq 'legacy-both-missing'
    || $scenario eq 'legacy-evidence-strict';
put("$root/build/reports/sbom.json",$merged);
for my $dir ($install,$package) {
 put("$dir/bin/perlonjava","launcher\n"); put("$dir/lib/perlonjava-5.44.0.jar",$jar);
 put("$dir/share/sbom/sbom.json",$merged);
 for my $n (qw(joni-LICENSE.txt joni-PERLONJAVA-NOTICE.md jcodings-LICENSE.txt)) { put("$dir/share/licenses/$n","$n\n") }
}
for my $n (qw(jperl jcpan jperldoc jprove)) { symlink("/opt/perlonjava/bin/$n","$root/.package/usr/local/bin/$n") or die $! }
put("$root/build/distributions/perlonjava_5.44.0_all.deb","DEB\n");
MAKE
    my $git = write_executable(File::Spec->catfile($tools, 'git-bin', 'git'), <<'GIT');
#!/usr/bin/perl
use strict; use warnings;
my $root; if (@ARGV>=2 && $ARGV[0] eq '-C') { $root=$ARGV[1]; splice @ARGV,0,2 }
open my $cf,'<',"$root/EXPECTED_COMMIT" or die $!; chomp(my $commit=<$cf>); close $cf;
if ($ARGV[0] eq 'rev-parse') { print "$commit\n"; exit 0 }
if ($ARGV[0] eq 'status') {
 open my $sf,'<',"$root/SCENARIO" or die $!; chomp(my $scenario=<$sf>); close $sf;
 if ($scenario eq 'post-link-source-mutation') {
  open my $of,'<',"$root/OUTPUT_ROOT" or die $!; chomp(my $output=<$of>); close $of;
  if (-e "$output/package-evidence.json") { open my $f,'>>',"$root/third_party/joni/LICENSE" or die $!; print {$f} "mutated\n"; close $f }
 }
 exit 0;
}
die "unexpected git: @ARGV";
GIT
    my $dpkg = write_executable(File::Spec->catfile($tools, 'dpkg-bin', 'dpkg-deb'), <<'DPKG');
#!/usr/bin/perl
use strict; use warnings; use File::Find qw(find); use File::Path qw(make_path); use File::Basename qw(dirname);
my($mode,$deb,$dest)=@ARGV; my $root=dirname(dirname(dirname($deb)));
if ($mode eq '--field') { print "Package: perlonjava\nVersion: 5.44.0\nArchitecture: all\nMaintainer: Flavio Soibelmann Glock <fglock\@gmail.com>\n"; exit 0 }
if ($mode eq '--contents') { print "-rw-r--r-- root/root 4 2026-01-01 00:00 ./opt/perlonjava/lib/perlonjava-5.44.0.jar\n"; exit 0 }
die "bad mode" unless $mode eq '--extract'; my $tree="$root/.package";
find({no_chdir=>1,wanted=>sub{return if $_ eq $tree; my $rel=substr($_,length($tree)+1); my $to="$dest/$rel";
 if(-d $_){make_path($to);return} make_path(dirname($to)); if(-l $_){symlink(readlink($_),$to) or die $!;return}
 open my $i,'<:raw',$_ or die $!; open my $o,'>:raw',$to or die $!; local $/; print {$o} <$i>; close $i; close $o;}},$tree); exit 0;
DPKG
    my $jar = write_executable(File::Spec->catfile($tools, 'java-bin', 'jar'),
        "#!/usr/bin/perl\nexit 0;\n");
    my $java = write_executable(File::Spec->catfile($tools, 'java-bin', 'java'), <<'JAVA');
#!/usr/bin/perl
use strict; use warnings; my $root=$ARGV[1]; $root=~s{/target/.*\z}{};
open my $f,'<',"$root/EXPECTED_COMMIT" or die $!; chomp(my $c=<$f>); print "git_commit_id='".substr($c,0,12)."';\n";
JAVA
    for my $name (qw(verify-joni-distribution.pl verify-joni-packaging.pl)) {
        write_executable(File::Spec->catfile($source, 'dev', 'regex', 'tools', $name),
            "#!/usr/bin/perl\nprint qq(ok\\n); exit 0;\n");
    }
    write_executable(File::Spec->catfile($source, 'dev', 'regex', 'tools',
        'verify_notice_license.pl'), <<'NOTICE');
#!/usr/bin/perl
use strict; use warnings; use Digest::SHA qw(sha256_hex); use JSON::PP;
my %o; while (@ARGV) { my $k=shift @ARGV; next if $k eq '--strict'; $k=~s/^--//; $o{$k}=shift @ARGV }
sub bytes { open my $f,'<:raw',$_[0] or die $!; local $/; return <$f> }
open my $sf,'<',"$o{'source-root'}/SCENARIO" or die $!; chomp(my $scenario=<$sf>); close $sf;
my $d={schema_version=>1,kind=>'notice-license',verified=>JSON::PP::true,
 missing_notices=>0,changed_notices=>0,missing_licenses=>0,changed_licenses=>0,
 jar_path=>$o{jar},jar_sha256=>sha256_hex(bytes($o{jar})),sbom_path=>$o{sbom},
 sbom_sha256=>sha256_hex(bytes($o{sbom})),source_root=>$o{'source-root'},
 notices=>[],components=>[],relationships=>[]};
$d={verified=>JSON::PP::true,jar_sha256=>sha256_hex(bytes($o{jar})),
 sbom_sha256=>sha256_hex(bytes($o{sbom}))}
 if $scenario eq 'legacy-both-missing' || $scenario eq 'legacy-evidence-strict';
$d->{unexpected}=1 if $scenario eq 'extra-notice-field';
open my $out,'>:raw',$o{output} or die $!; print {$out} JSON::PP->new->canonical->encode($d); close $out; exit 0;
NOTICE
    return { base => $base, source => abs_path($source), output => abs_path($output),
        scenario => $scenario,
        tools => { make => abs_path($make), git => abs_path($git),
            dpkg => abs_path($dpkg), java => abs_path($java), jar => abs_path($jar) } };
}

sub collect_descriptors {
    my ($value, $found) = @_;
    if (ref($value) eq 'HASH' && exists $value->{path}) { push @$found, $value; return }
    collect_descriptors($_, $found) for ref($value) eq 'HASH' ? values %$value
        : ref($value) eq 'ARRAY' ? @$value : ();
}
sub write_executable { my($p,$b)=@_; write_file($p,$b); chmod 0755,$p or die $!; return $p }
sub write_file { my($p,$b)=@_; open my $f,'>:raw',$p or die $!; print {$f} $b; close $f or die $!; return $p }
sub read_file { my($p)=@_; open my $f,'<:raw',$p or die $!; local $/; my $b=<$f>; close $f; return $b }
sub load_json { JSON::PP->new->decode(read_file($_[0])) }
sub sha256_file { sha256_hex(read_file($_[0])) }
sub entries { my($d)=@_; opendir my $f,$d or die $!; my @e=sort grep {$_ ne '.'&&$_ ne '..'} readdir $f; closedir $f; return @e }
