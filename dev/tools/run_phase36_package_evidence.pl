#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path getcwd);
use Digest::SHA qw(sha256_hex);
use File::Basename qw(dirname basename);
use File::Find qw(find);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir tempfile);
use Fcntl qw(:DEFAULT :mode);
use Getopt::Long qw(GetOptions);
use JSON::PP;
use MIME::Base64 qw(encode_base64);
use POSIX qw(setpgid strftime WIFEXITED WEXITSTATUS WIFSIGNALED WTERMSIG);
use Time::HiRes qw(time sleep);

my %option = (timeout => 1800);
my $help;
GetOptions(
    'source-root=s' => \$option{source_root},
    'expected-commit=s' => \$option{expected_commit},
    'output-root=s' => \$option{output_root},
    'make=s' => \$option{make},
    'perl=s' => \$option{perl},
    'git=s' => \$option{git},
    'dpkg-deb=s' => \$option{dpkg_deb},
    'java=s' => \$option{java},
    'jar=s' => \$option{jar},
    'timeout=i' => \$option{timeout},
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV;
for my $name (qw(source_root expected_commit output_root make perl git dpkg_deb java jar)) {
    die "--" . ($name =~ s/_/-/gr) . " is required\n"
        unless defined $option{$name} && length $option{$name};
}
die "--expected-commit must be a full lowercase Git SHA\n"
    unless $option{expected_commit} =~ /\A[0-9a-f]{40}\z/;
die "--timeout must be positive\n" unless $option{timeout} > 0;

my $source = canonical_directory($option{source_root}, 'source root', 1);
my $output = canonical_directory($option{output_root}, 'output root', 1);
die "Source root and output root must be disjoint\n"
    if contains_path($source, $output) || contains_path($output, $source);
my @occupied = directory_entries($output);
die "Sealed output root is not empty: $output\n" if @occupied;

my %tool;
for my $name (qw(make perl git dpkg_deb java jar)) {
    $tool{$name} = executable_record($option{$name}, $name);
}
die "Trusted --jar executable must retain the command name jar\n"
    unless basename($tool{jar}{path}) eq 'jar';
die "Trusted --java executable must retain the command name java\n"
    unless basename($tool{java}{path}) eq 'java';
die "Trusted java and jar must come from the same canonical installation directory\n"
    unless dirname($tool{java}{path}) eq dirname($tool{jar}{path});

my @verifier_spec = (
    [distribution => 'verify-joni-distribution.pl'],
    [packaging => 'verify-joni-packaging.pl'],
    [notice_license => 'verify_phase36_notice_license.pl'],
);
my %verifier;
for my $spec (@verifier_spec) {
    my ($name, $file) = @$spec;
    my $path = safe_source_file($source, 'dev', 'tools', $file);
    $verifier{$name} = file_record($path, "accepted $name verifier");
}
my @config_names = (
    ['Makefile'], ['build.gradle'], ['settings.gradle'],
    ['gradle', 'wrapper', 'gradle-wrapper.properties'],
);
my @configs = map {
    file_record(safe_source_file($source, @$_), 'packaging configuration')
} @config_names;
my %protected = map { $_->{path} => $_->{sha256} }
    (values(%tool), values(%verifier), @configs);

my $work = tempdir('phase36-package-evidence-XXXXXX', TMPDIR => 1, CLEANUP => 1);
my @commands;
my %generated_files;
my %base_env = (
    PATH => join(($^O eq 'MSWin32' ? ';' : ':'), dirname($tool{jar}{path}),
        dirname($tool{perl}{path}), ($ENV{PATH} // '')),
    LC_ALL => 'C', LANG => 'C', TZ => 'UTC',
    PERL5OPT => undef, PERL5LIB => undef,
    map { defined($ENV{$_}) ? ($_ => $ENV{$_}) : () }
        qw(HOME TMPDIR JAVA_HOME GRADLE_USER_HOME SYSTEMROOT COMSPEC PATHEXT),
);

verify_source();
verify_protected();
reject_stale_outputs();

my $build_log = File::Spec->catfile($work, 'make-deb.log');
run_checked('make-deb', [$tool{make}{path}, '-C', $source, 'deb'], $build_log,
    \%base_env);
verify_source();
verify_protected();

my $install_parent = safe_existing_directory($source, 'build', 'install');
my @install_names = directory_entries($install_parent);
die "Expected exactly one installDist tree (found " . scalar(@install_names) . ")\n"
    unless @install_names == 1;
my $install = safe_existing_directory($install_parent, $install_names[0]);
my $target = safe_existing_directory($source, 'target');
my @target_jars = grep { /\.jar\z/i && -f File::Spec->catfile($target, $_) }
    directory_entries($target);
my @standalone = grep { /\Aperlonjava-[^\/]+\.jar\z/ } @target_jars;
die "Expected exactly one standalone JAR and no unexpected JAR artifacts (found "
    . join(', ', @target_jars) . ")\n"
    unless @target_jars == 1 && @standalone == 1;
my $jar = safe_existing_file($target, $target_jars[0]);
my $sbom = safe_existing_file($source, 'build', 'reports', 'sbom.json');
my $distributions = safe_existing_directory($source, 'build', 'distributions');
my @distribution_entries = directory_entries($distributions);
my @debs = grep { /\.deb\z/ && -f File::Spec->catfile($distributions, $_) }
    @distribution_entries;
die "Unexpected package artifacts: " . join(', ', @distribution_entries) . "\n"
    unless @distribution_entries == 1 && @debs == 1;
my $deb = safe_existing_file($distributions, $debs[0]);
%generated_files = map { $_ => sha256_file($_) } ($jar, $sbom, $deb);

my $install_tree = tree_record($install, {}, 'installDist');
my @install_jars = grep { $_->{type} eq 'file' && $_->{path} =~ m{\Alib/[^/]+\.jar\z} }
    @{$install_tree->{entries}};
die "installDist must contain exactly one runtime JAR\n" unless @install_jars == 1;
my $installed_jar = safe_existing_file($install, split m{/}, $install_jars[0]{path});
assert_same_file($jar, $installed_jar, 'standalone and installDist JAR');
assert_sbom_commit($sbom, $option{expected_commit});

my $notice_output = File::Spec->catfile($work, 'notice-license.json');
run_checked('verify-distribution',
    [$tool{perl}{path}, $verifier{distribution}{path}, $install],
    File::Spec->catfile($work, 'verify-distribution.log'), \%base_env);
run_checked('verify-packaging',
    [$tool{perl}{path}, $verifier{packaging}{path}, '--strict', $jar, $sbom],
    File::Spec->catfile($work, 'verify-packaging.log'), \%base_env);
run_checked('verify-notice-license',
    [$tool{perl}{path}, $verifier{notice_license}{path}, '--strict',
        '--source-root', $source, '--jar', $jar, '--sbom', $sbom,
        '--output', $notice_output],
    File::Spec->catfile($work, 'verify-notice-license.log'), \%base_env);
my $notice = load_json($notice_output, 'notice/license verifier output');
die "Notice/license verifier did not report success\n" unless $notice->{verified};
die "Notice/license verifier JAR identity mismatch\n"
    unless ($notice->{jar_sha256} // '') eq sha256_file($jar);
die "Notice/license verifier SBOM identity mismatch\n"
    unless ($notice->{sbom_sha256} // '') eq sha256_file($sbom);
verify_source();
verify_protected();

my $deb_info = File::Spec->catfile($work, 'dpkg-info.log');
run_checked('dpkg-info', [$tool{dpkg_deb}{path}, '--info', $deb], $deb_info,
    \%base_env);
my $deb_contents = File::Spec->catfile($work, 'dpkg-contents.log');
run_checked('dpkg-contents', [$tool{dpkg_deb}{path}, '--contents', $deb],
    $deb_contents, \%base_env);
assert_safe_dpkg_listing(read_raw($deb_contents));
my $extract = File::Spec->catdir($work, 'deb-root');
make_path($extract);
$extract = abs_path($extract) or die "Cannot resolve private Debian extraction root\n";
run_checked('dpkg-extract', [$tool{dpkg_deb}{path}, '--extract', $deb, $extract],
    File::Spec->catfile($work, 'dpkg-extract.log'), \%base_env);
my %allowed_links = (
    'usr/local/bin/jperl' => '/opt/perlonjava/bin/jperl',
    'usr/local/bin/jcpan' => '/opt/perlonjava/bin/jcpan',
    'usr/local/bin/jperldoc' => '/opt/perlonjava/bin/jperldoc',
    'usr/local/bin/jprove' => '/opt/perlonjava/bin/jprove',
);
my $deb_tree = tree_record($extract, \%allowed_links, 'Debian package');
my $package_root = safe_existing_directory($extract, 'opt', 'perlonjava');
my $deb_jar = safe_existing_file($package_root, 'lib', basename($jar));
my $deb_sbom = safe_existing_file($package_root, 'share', 'sbom', 'sbom.json');
assert_same_file($jar, $deb_jar, 'standalone and Debian JAR');
assert_same_file($sbom, $deb_sbom, 'external and Debian SBOM');
for my $name (qw(joni-LICENSE.txt joni-PERLONJAVA-NOTICE.md jcodings-LICENSE.txt)) {
    assert_same_file(
        safe_existing_file($install, 'share', 'licenses', $name),
        safe_existing_file($package_root, 'share', 'licenses', $name),
        "installDist and Debian notice $name");
}
assert_tree_subset_equal($install, $package_root);
my $final_install_tree = tree_record($install, {}, 'final installDist');
die "installDist mutated during package verification\n"
    unless $final_install_tree->{tree_sha256} eq $install_tree->{tree_sha256};
verify_source();
verify_protected();

my @artifacts = map { file_record($_->[0], $_->[1]) } (
    [$jar, 'standalone JAR'], [$sbom, 'merged SBOM'], [$deb, 'Debian package'],
    [$notice_output, 'notice/license verification'],
);
my $document = {
    schema_version => 1,
    kind => 'phase36-package-evidence',
    status => 'pass',
    identity => {
        source_root => $source,
        source_commit => $option{expected_commit},
        jar_sha256 => sha256_file($jar),
        sbom_sha256 => sha256_file($sbom),
    },
    build_contract => {
        make_target => 'deb',
        install_dist_via_build_deb_dependency => JSON::PP::true,
        timeout_seconds => $option{timeout},
        environment => \%base_env,
        environment_sha256 => sha256_hex(canonical(\%base_env)),
    },
    tools => { map { $_ => $tool{$_} } sort keys %tool },
    verifiers => { map { $_ => $verifier{$_} } sort keys %verifier },
    configs => \@configs,
    commands => \@commands,
    artifacts => \@artifacts,
    trees => { install_dist => $install_tree, debian => $deb_tree },
    notice_license => $notice,
};
verify_source();
verify_protected();
die "Sealed output root changed during production\n" if directory_entries($output);
publish_atomic(File::Spec->catfile($output, 'package-evidence.json'), canonical_pretty($document));
print File::Spec->catfile($output, 'package-evidence.json'), "\n";

sub run_checked {
    my ($name, $argv, $log, $environment) = @_;
    verify_tools();
    verify_generated_files();
    my $result = run_child($argv, $log, $option{timeout}, $environment);
    verify_tools();
    verify_generated_files();
    my $bytes = read_raw($log);
    my $record = {
        name => $name, argv => $argv, argv_sha256 => sha256_hex(canonical($argv)),
        log_sha256 => sha256_hex($bytes), log_size => length($bytes),
        log_encoding => 'base64', log_base64 => encode_base64($bytes, ''),
        started_at => $result->{started_at}, ended_at => $result->{ended_at},
        duration_seconds => $result->{duration_seconds},
        exit_code => $result->{exit_code}, signal => $result->{signal},
        timeout => $result->{timeout} ? JSON::PP::true : JSON::PP::false,
    };
    push @commands, $record;
    die "$name timed out after $option{timeout} seconds\n" if $result->{timeout};
    die "$name terminated by signal $result->{signal}\n" if $result->{signal};
    die "$name exited nonzero ($result->{exit_code})\n" if $result->{exit_code};
    return $record;
}

sub run_child {
    my ($argv, $log, $timeout, $environment) = @_;
    my $started = time;
    my $pid = fork();
    die "Cannot fork: $!\n" unless defined $pid;
    if (!$pid) {
        eval { setpgid(0, 0) };
        open STDIN, '<', File::Spec->devnull or die "Cannot redirect stdin: $!\n";
        open STDOUT, '>:raw', $log or die "Cannot open command log: $!\n";
        open STDERR, '>&', \*STDOUT or die "Cannot redirect stderr: $!\n";
        %ENV = map { defined $environment->{$_} ? ($_ => $environment->{$_}) : () }
            keys %$environment;
        exec { $argv->[0] } @$argv;
        die "Cannot execute $argv->[0]: $!\n";
    }
    eval { setpgid($pid, $pid) };
    my ($status, $timed_out);
    while (1) {
        my $waited = waitpid($pid, POSIX::WNOHANG());
        if ($waited == $pid) { $status = $?; last }
        die "waitpid failed: $!\n" if $waited < 0;
        if (time - $started >= $timeout) {
            $timed_out = 1;
            kill 'TERM', -$pid;
            my $deadline = time + 1;
            my $reaped;
            while (time < $deadline) {
                my $waited = waitpid($pid, POSIX::WNOHANG());
                if ($waited == $pid) { $status = $?; $reaped = 1; last }
                last if $waited < 0;
                sleep 0.05;
            }
            unless ($reaped) {
                kill 'KILL', -$pid;
                waitpid($pid, 0);
                $status = $?;
            }
            last;
        }
        sleep 0.05;
    }
    my $ended = time;
    return {
        started_at => timestamp($started), ended_at => timestamp($ended),
        duration_seconds => 0 + sprintf('%.6f', $ended - $started),
        timeout => $timed_out ? 1 : 0,
        exit_code => WIFEXITED($status) ? WEXITSTATUS($status) : 0,
        signal => WIFSIGNALED($status) ? WTERMSIG($status) : 0,
    };
}

sub verify_source {
    my $head_log = File::Spec->catfile($work, 'git-head-' . scalar(@commands) . '.log');
    my $head = run_checked('git-head', [$tool{git}{path}, '-C', $source,
        'rev-parse', 'HEAD'], $head_log, \%base_env);
    my $actual = read_raw($head_log); $actual =~ s/\s+\z//;
    die "Source commit mismatch: expected $option{expected_commit}, got $actual\n"
        unless $actual eq $option{expected_commit};
    my $status_log = File::Spec->catfile($work, 'git-status-' . scalar(@commands) . '.log');
    run_checked('git-status', [$tool{git}{path}, '-C', $source, 'status',
        '--porcelain=v1', '--untracked-files=all'], $status_log, \%base_env);
    die "Source tree is dirty or mutated\n" if length read_raw($status_log);
}

sub reject_stale_outputs {
    my @paths = (
        File::Spec->catdir($source, 'build', 'install'),
        File::Spec->catdir($source, 'build', 'distributions'),
        File::Spec->catfile($source, 'build', 'reports', 'sbom.json'),
    );
    push @paths, glob(File::Spec->catfile($source, 'target', 'perlonjava-*.jar'));
    my @stale = grep { -e $_ || -l $_ } @paths;
    die "Stale preexisting package output: $stale[0]\n" if @stale;
}

sub verify_protected {
    for my $path (sort keys %protected) {
        die "Protected tool/config disappeared: $path\n" unless -f $path;
        die "Protected tool/config mutated: $path\n"
            unless sha256_file($path) eq $protected{$path};
    }
}

sub verify_tools {
    for my $name (sort keys %tool) {
        my $path = $tool{$name}{path};
        die "Trusted $name executable disappeared: $path\n" unless -f $path && -x $path;
        die "Trusted $name executable mutated: $path\n"
            unless sha256_file($path) eq $tool{$name}{sha256};
    }
}

sub verify_generated_files {
    for my $path (sort keys %generated_files) {
        die "Generated package artifact disappeared: $path\n" unless -f $path;
        die "Generated package artifact mutated: $path\n"
            unless sha256_file($path) eq $generated_files{$path};
    }
}

sub assert_sbom_commit {
    my ($path, $expected) = @_;
    my $doc = load_json($path, 'merged SBOM');
    my @values;
    for my $component (@{$doc->{components} // []}) {
        next unless ref($component) eq 'HASH';
        next unless ($component->{group} // '') eq 'org.perlonjava.fork'
            && ($component->{name} // '') eq 'joni-fork';
        push @values, map { ref($_) eq 'HASH'
            && ($_->{name} // '') eq 'perlonjava:source-commit'
            ? ($_->{value} // '') : () } @{$component->{properties} // []};
    }
    die "Merged SBOM must bind exactly one Joni source commit\n" unless @values == 1;
    die "Merged SBOM source commit mismatch\n" unless $values[0] eq $expected;
}

sub assert_safe_dpkg_listing {
    my ($text) = @_;
    die "dpkg-deb contents listing is empty or malformed\n" unless $text =~ /\S/;
    for my $line (split /\n/, $text) {
        next unless $line =~ /\s(\.\/\S.*)\z/;
        my $path = $1; $path =~ s/\s+->\s+.*\z//;
        die "Debian package contains unsafe path $path\n"
            if $path =~ /(?:\A|\/)\.\.(?:\/|\z)/ || $path =~ m{\A/};
    }
}

sub tree_record {
    my ($root, $allowed_links, $label) = @_;
    my @entries;
    find({ no_chdir => 1, follow => 0, wanted => sub {
        return if $_ eq $root;
        my $relative = File::Spec->abs2rel($_, $root); $relative =~ s{\\}{/}g;
        die "$label contains path escape $relative\n"
            if $relative =~ /(?:\A|\/)\.\.(?:\/|\z)/;
        my @stat = lstat($_); die "Cannot lstat $_: $!\n" unless @stat;
        my $mode = $stat[2];
        my $entry = { path => $relative, mode => sprintf('%04o', S_IMODE($mode)) };
        if (S_ISLNK($mode)) {
            my $target = readlink($_); die "Cannot read symlink $_: $!\n" unless defined $target;
            die "$label contains unexpected symlink $relative -> $target\n"
                unless exists $allowed_links->{$relative} && $allowed_links->{$relative} eq $target;
            $entry->{type} = 'symlink'; $entry->{target} = $target;
        } elsif (S_ISDIR($mode)) { $entry->{type} = 'directory' }
        elsif (S_ISREG($mode)) {
            $entry->{type} = 'file'; $entry->{size} = $stat[7];
            $entry->{sha256} = sha256_file($_);
        } else { die "$label contains unsupported file type $relative\n" }
        push @entries, $entry;
    }}, $root);
    @entries = sort { $a->{path} cmp $b->{path} } @entries;
    return { root => $root, entries => \@entries,
        tree_sha256 => sha256_hex(canonical(\@entries)) };
}

sub assert_tree_subset_equal {
    my ($expected_root, $actual_root) = @_;
    my $expected = tree_record($expected_root, {}, 'installDist comparison');
    for my $entry (@{$expected->{entries}}) {
        next if $entry->{type} eq 'directory';
        my $actual = File::Spec->catfile($actual_root, split m{/}, $entry->{path});
        die "Debian package is missing installDist entry $entry->{path}\n" unless -f $actual;
        die "Debian package differs from installDist at $entry->{path}\n"
            unless sha256_file($actual) eq $entry->{sha256};
    }
}

sub assert_same_file {
    my ($a, $b, $label) = @_;
    die "$label bytes differ\n" unless sha256_file($a) eq sha256_file($b);
}

sub executable_record {
    my ($path, $label) = @_;
    die "Trusted $label executable must be an absolute path\n"
        unless File::Spec->file_name_is_absolute($path);
    my $canonical = abs_path($path) or die "Cannot resolve trusted $label executable\n";
    die "Trusted $label executable is not a regular executable file\n"
        unless -f $canonical && -x $canonical;
    return { path => $canonical, requested_path => $path, sha256 => sha256_file($canonical) };
}

sub file_record {
    my ($path, $label) = @_;
    die "$label is missing or empty: $path\n" unless -f $path && -s $path;
    my @stat = stat($path);
    return { path => $path, sha256 => sha256_file($path), size => $stat[7] };
}

sub safe_source_file { return safe_existing_file(shift, @_) }
sub safe_existing_file {
    my ($root, @parts) = @_;
    my $path = File::Spec->catfile($root, @parts);
    my $canonical = abs_path($path) or die "Missing file $path\n";
    die "Path escapes root: $path\n" unless contains_path($root, $canonical);
    die "Expected regular file: $path\n" unless -f $canonical;
    return $canonical;
}
sub safe_existing_directory {
    my ($root, @parts) = @_;
    my $path = File::Spec->catdir($root, @parts);
    my $canonical = abs_path($path) or die "Missing directory $path\n";
    die "Path escapes root: $path\n" unless contains_path($root, $canonical);
    die "Expected directory: $path\n" unless -d $canonical;
    return $canonical;
}
sub canonical_directory {
    my ($path, $label, $require_exact) = @_;
    die "$label must be an absolute path\n" unless File::Spec->file_name_is_absolute($path);
    my $canonical = abs_path($path) or die "Cannot resolve $label $path\n";
    die "$label is not a directory\n" unless -d $canonical;
    die "$label must be canonical (no symlink or dot components)\n"
        if $require_exact && File::Spec->canonpath($path) ne $canonical;
    return $canonical;
}
sub contains_path {
    my ($root, $path) = @_;
    my $separator = $^O eq 'MSWin32' ? '\\' : '/';
    return $path eq $root || index($path, $root . $separator) == 0;
}
sub directory_entries {
    my ($dir) = @_;
    opendir my $fh, $dir or die "Cannot open directory $dir: $!\n";
    my @entries = sort grep { $_ ne '.' && $_ ne '..' } readdir $fh;
    closedir $fh or die "Cannot close directory $dir: $!\n";
    return @entries;
}
sub load_json {
    my ($path, $label) = @_;
    my $doc = eval { JSON::PP->new->utf8->decode(read_raw($path)) };
    die "Malformed $label JSON: $@\n" unless ref($doc) eq 'HASH';
    return $doc;
}
sub sha256_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $sha = Digest::SHA->new(256); $sha->addfile($fh);
    close $fh or die "Cannot close $path: $!\n";
    return $sha->hexdigest;
}
sub read_raw {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $path: $!\n";
    my $bytes = do { local $/; <$fh> };
    close $fh or die "Cannot close $path: $!\n";
    return $bytes;
}
sub canonical { return JSON::PP->new->utf8->canonical->encode($_[0]) }
sub canonical_pretty { return JSON::PP->new->utf8->canonical->pretty->encode($_[0]) }
sub timestamp { return strftime('%Y-%m-%dT%H:%M:%SZ', gmtime($_[0])) }
sub publish_atomic {
    my ($final, $bytes) = @_;
    my $output_root = dirname($final);
    my $parent = dirname($output_root);
    my ($fh, $temporary) = tempfile('.package-evidence.XXXXXX', DIR => $parent, UNLINK => 0);
    binmode $fh, ':raw';
    unless (print {$fh} $bytes) {
        close $fh; unlink $temporary;
        die "Cannot write temporary evidence: $!\n";
    }
    unless (close $fh) { unlink $temporary; die "Cannot close temporary evidence: $!\n" }
    if (-e $final || -l $final) {
        unlink $temporary;
        die "Evidence destination appeared during publication\n";
    }
    unless (rename($temporary, $final)) {
        my $error = $!; unlink $temporary;
        die "Cannot atomically publish evidence: $error\n";
    }
}
sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: run_phase36_package_evidence.pl --source-root ABSOLUTE_CANONICAL_DIR
       --expected-commit FULL_SHA --output-root EMPTY_CANONICAL_DIR
       --make ABS_EXE --perl ABS_EXE --git ABS_EXE --dpkg-deb ABS_EXE
       --java ABS_EXE --jar ABS_EXE [--timeout SECONDS]

Produces final-freeze Phase 36 package evidence. The source must be a clean
checkout at exactly EXPECTED_COMMIT and must have no preexisting installDist,
standalone-JAR, SBOM, or Debian outputs. Trusted make executes `make -C SOURCE
deb`; the accepted build graph makes buildDeb depend on fresh installDist and
its distribution verifier. Every subprocess uses argv execution without a
shell and a common wall timeout. Trusted Java and jar must be from one canonical
installation directory; that directory and trusted Perl's canonical directory
lead PATH without copying or relocating any executable. The three accepted
distribution, strict Joni packaging/SBOM, and strict notice/license verifiers
are invoked from the selected source root. Trusted dpkg-deb validates, lists,
and privately extracts the package for byte binding and path checks.

Transient logs stay outside OUTPUT_ROOT. On success exactly one structured
artifact, package-evidence.json, is atomically renamed into OUTPUT_ROOT; any
failure leaves the initially empty output root empty.
USAGE
    exit $status;
}
