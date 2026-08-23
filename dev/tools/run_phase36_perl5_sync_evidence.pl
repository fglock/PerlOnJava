#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA qw(sha256_hex);
use Fcntl qw(O_CREAT O_EXCL O_WRONLY);
use File::Basename qw(dirname);
use File::Spec;
use Getopt::Long qw(GetOptions);
use JSON::PP;
use POSIX qw(WNOHANG);
use Time::HiRes qw(sleep time);

my %option = (timeout => 1800);
my $capture_sequence = 0;
my $help;
GetOptions(
    'source-root=s' => \$option{source_root},
    'perl5-root=s' => \$option{perl5_root},
    'perl=s' => \$option{perl},
    'git=s' => \$option{git},
    'make=s' => \$option{make},
    'repository=s' => \$option{repository},
    'expected-source-commit=s' => \$option{expected_source_commit},
    'output=s' => \$option{output},
    'timeout=i' => \$option{timeout},
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV;
for my $name (qw(source_root perl5_root perl git make repository
        expected_source_commit output)) {
    (my $display = $name) =~ tr/_/-/;
    die "--$display is required\n"
        unless defined $option{$name} && length $option{$name};
}
die "--timeout must be positive\n" unless $option{timeout} > 0;
die "--expected-source-commit must be a full lowercase Git SHA\n"
    unless $option{expected_source_commit} =~ /\A[0-9a-f]{40}\z/;
die "--repository contains a newline or NUL\n"
    if $option{repository} =~ /[\r\n\0]/;
sanitize_environment();

my $source = canonical_directory($option{source_root}, 'source root');
my $perl5 = canonical_directory($option{perl5_root}, 'perl5 root');
my %executable = map {
    $_ => executable_identity($option{$_}, $_)
} qw(perl git make);
my $output = absolute_output($option{output});
my $parent = dirname($output);
die "Output parent is not a directory: $parent\n" unless -d $parent;
die "Unsafe output path: $output\n" if unsafe_output($output);
die "Refusing to overwrite output $output\n" if -e $output || -l $output;
die "Output must be outside the source and perl5 worktrees\n"
    if path_is_inside($output, $source) || path_is_inside($output, $perl5);
my $linked_perl5 = abs_path(File::Spec->catdir($source, 'perl5'));
die "Source perl5 path does not resolve to the authority-selected perl5 root\n"
    unless defined $linked_perl5 && $linked_perl5 eq $perl5;

my %input_file;
my $producer_path = abs_path($0);
die "Cannot resolve producer identity\n" unless defined $producer_path;
$input_file{producer} = file_identity($producer_path, 'producer');
for my $entry (
    [config => qw(dev import-perl5 config.yaml)],
    [sync_script => qw(dev import-perl5 sync.pl)],
    [update_script => qw(dev import-perl5 update_perl5.pl)],
    [makefile => 'Makefile'],
) {
    my ($name, @parts) = @$entry;
    $input_file{$name} = file_identity(
        File::Spec->catfile($source, @parts), $name);
}
my $protected_before = protected_manifest($input_file{config}{path}, $source);
my $name_before = name_manifest($source, $perl5, 0);

my $source_before = checkout_identity($source, 'source', 'strict');
die "Source commit differs from --expected-source-commit\n"
    unless $source_before->{commit} eq $option{expected_source_commit};
die "Source worktree is dirty before sync evidence capture\n"
    unless $source_before->{clean};
my $perl_before = checkout_identity($perl5, 'perl5', 'perl5');
die "Perl5 worktree is not clean under the generated-Name.pl policy before sync evidence capture\n"
    unless $perl_before->{acceptance_clean};
my $remote_before = remote_identity($perl5, $option{repository});

my ($log_path, $stage_path) = temporary_paths($output);
my $published = 0;
my $ok = eval {
    my @argv = ($executable{make}{path}, '-C', $source,
        "PERL=$executable{perl}{path}", 'perl5-sync-check');
    my $command_environment = {
        PERL5_REPOSITORY => $option{repository},
        FILTER => undef,
        LC_ALL => 'C', LANG => 'C',
    };
    my $run = run_bounded(\@argv, $log_path, $option{timeout},
        $command_environment);
    validate_run($run, $log_path);
    my $log = read_bounded($log_path, 16 * 1024 * 1024, 'sync log');
    my $markers = validate_log($log, $remote_before->{tip});

    my $source_after = checkout_identity($source, 'source', 'strict');
    my $perl_after = checkout_identity($perl5, 'perl5', 'perl5');
    die "Source commit changed during sync evidence capture\n"
        unless $source_after->{commit} eq $source_before->{commit};
    die "Source worktree is dirty after sync evidence capture\n"
        unless $source_after->{clean};
    die "Perl5 worktree is not clean under the generated-Name.pl policy after sync evidence capture\n"
        unless $perl_after->{acceptance_clean};
    die "Perl5 checkout did not finish at the advertised latest tip\n"
        unless $perl_after->{commit} eq $remote_before->{tip};
    my $remote_after = remote_identity($perl5, $option{repository});
    die "Remote default branch or latest tip changed during capture; retry\n"
        unless $remote_after->{branch} eq $remote_before->{branch}
            && $remote_after->{tip} eq $remote_before->{tip};
    my $protected_after = protected_manifest($input_file{config}{path}, $source);
    die "Protected targets mutated during sync evidence capture\n"
        unless canonical($protected_after) eq canonical($protected_before);
    my $name_after = name_manifest($source, $perl5, 1);
    verify_file_identities(\%input_file, \%executable);

    my $document = {
        schema_version => 1,
        kind => 'phase36-perl5-sync-evidence',
        status => 'pass',
        expected_source_commit => $option{expected_source_commit},
        timeout_seconds => 0 + $option{timeout},
        repository => $option{repository},
        command => {
            argv => \@argv,
            environment => $command_environment,
            exit_code => 0 + $run->{exit_code},
            signal => 0 + $run->{signal},
            timeout => JSON::PP::false,
            duration_seconds => 0 + $run->{duration_seconds},
            complete_log => $log,
            complete_log_sha256 => sha256_hex($log),
        },
        source => { before => $source_before, after => $source_after },
        perl5 => { before => $perl_before, after => $perl_after },
        upstream => { before => $remote_before, after => $remote_after },
        tools => \%executable,
        inputs => \%input_file,
        sync_markers => $markers,
        protected_targets => $protected_after,
        unicode_name => { before => $name_before, after => $name_after },
        final_source_commit => $source_after->{commit},
    };
    write_stage($stage_path, canonical($document));
    die "Source or Perl5 identity changed before atomic publication\n"
        unless canonical(checkout_identity($source, 'source', 'strict'))
                eq canonical($source_after)
            && canonical(checkout_identity($perl5, 'perl5', 'perl5'))
                eq canonical($perl_after);
    verify_file_identities(\%input_file, \%executable);
    die "Refusing publication race at $output\n" unless link $stage_path, $output;
    $published = 1;
    1;
};
my $error = $@;
unlink $stage_path if defined $stage_path && (-e $stage_path || -l $stage_path);
unlink $log_path if defined $log_path && (-e $log_path || -l $log_path);
die $error unless $ok;
print "$output\n";

sub remote_identity {
    my ($root, $expected_repository) = @_;
    my $branch = git_line($root, 'symbolic-ref', '--quiet', '--short', 'HEAD');
    die "Perl5 checkout is detached\n" unless length $branch;
    my $upstream = git_line($root, 'rev-parse', '--abbrev-ref',
        '--symbolic-full-name', '@{upstream}');
    my ($remote) = split m{/}, $upstream, 2;
    die "Perl5 checkout has no valid upstream\n"
        unless defined $remote && length $remote;
    die "Perl5 upstream is not $remote/$branch\n" unless $upstream eq "$remote/$branch";
    my $url = git_line($root, 'remote', 'get-url', $remote);
    die "Perl5 remote does not match --repository\n"
        unless normalized_repository($url) eq normalized_repository($expected_repository);
    my $head = git_capture($root, 'ls-remote', '--symref', $remote, 'HEAD');
    my @default = $head =~ /^ref:\s+refs\/heads\/(\S+)\s+HEAD$/mg;
    die "Remote advertisement has no unique default branch\n" unless @default == 1;
    die "Perl5 branch is not the remote-advertised default branch\n"
        unless $branch eq $default[0];
    my $reference = "refs/heads/$branch";
    my $tips = git_capture($root, 'ls-remote', $remote, $reference);
    my @tip = $tips =~ /^([0-9a-f]{40})\s+\Q$reference\E$/mg;
    die "Remote advertisement has no unique latest branch tip\n" unless @tip == 1;
    return { remote => $remote, repository_url => $url, branch => $branch,
        upstream => $upstream, tip => $tip[0] };
}

sub validate_log {
    my ($log, $tip) = @_;
    die "Sync log contains FILTER/partial-sync evidence\n"
        if $log =~ /(?:Filtered mode|--only|\bFILTER\s*=)/i;
    my @upstream = $log =~ /^Perl upstream commit:\s*([0-9a-f]{40})\s*$/mg;
    my @verified = $log =~ /^Verified remote tip:\s*([0-9a-f]{40})\s*$/mg;
    die "Sync log has malformed or duplicate upstream commit markers\n"
        unless @upstream == 1 && $upstream[0] eq $tip;
    die "Sync log has malformed or duplicate verified-tip markers\n"
        unless @verified == 1 && $verified[0] eq $tip;
    my @full = $log =~ /^Full manifest:\s*(\d+) import\(s\) to process\.\s*$/mg;
    die "Sync log does not contain exactly two full-manifest passes\n"
        unless @full == 2 && $full[0] > 0 && $full[0] == $full[1];
    my @second = $log =~ /^Running second sync for idempotence verification\.\s*$/mg;
    my @idempotent = $log =~ /^Idempotence verified: second sync changed no imported outputs\.\s*$/mg;
    die "Sync log is missing the unique second-pass marker\n" unless @second == 1;
    die "Sync log is missing the unique idempotence marker\n" unless @idempotent == 1;
    my @success = $log =~ /^\s*Successful:\s*(\d+)\s*$/mg;
    my @errors = $log =~ /^\s*Errors:\s*(\d+)\s*$/mg;
    die "Sync log does not contain two complete summaries\n"
        unless @success == 2 && @errors == 2;
    die "Sync log reports incomplete or inconsistent passes\n"
        unless $success[0] == $full[0] && $success[1] == $full[1]
            && $errors[0] == 0 && $errors[1] == 0;
    my @protected = $log =~ /^Protected paths from config \((\d+)\):\s*$/mg;
    die "Sync log does not expose protected targets on both passes\n"
        unless @protected == 2 && $protected[0] == $protected[1];
    return { full_manifest_count => 0 + $full[0], pass_count => 2,
        successful_per_pass => [map { 0 + $_ } @success],
        errors_per_pass => [map { 0 + $_ } @errors],
        protected_count_per_pass => [map { 0 + $_ } @protected],
        second_pass_seen => JSON::PP::true, idempotence_verified => JSON::PP::true };
}

sub protected_manifest {
    my ($config, $root) = @_;
    my $text = read_bounded($config, 4 * 1024 * 1024, 'sync config');
    my (@rows, $target, $protected);
    for my $line (split /\n/, $text) {
        if ($line =~ /^\s*-\s+source:/) {
            push @rows, [$target, $protected] if defined $target;
            ($target, $protected) = (undef, 0);
        } elsif ($line =~ /^\s+target:\s*(.*?)\s*$/) {
            $target = yaml_scalar($1);
        } elsif ($line =~ /^\s+protected:\s*(.*?)\s*$/) {
            $protected = $1 =~ /\A(?:true|yes|1)\z/i ? 1 : 0;
        }
    }
    push @rows, [$target, $protected] if defined $target;
    my @manifest;
    for my $row (@rows) {
        next unless $row->[1];
        my $relative = safe_relative($row->[0], 'protected target');
        my $path = File::Spec->catfile($root, split m{/}, $relative);
        die "Protected target is missing or not a regular file: $relative\n" unless -f $path;
        push @manifest, { path => $relative, sha256 => sha256_file($path) };
    }
    die "Sync config contains no protected targets\n" unless @manifest;
    my %seen;
    die "Sync config repeats a protected target\n"
        if grep { $seen{$_->{path}}++ } @manifest;
    return \@manifest;
}

sub yaml_scalar {
    my ($value) = @_;
    $value =~ s/^['"]//; $value =~ s/['"]$//;
    return $value;
}

sub name_manifest {
    my ($source_root, $perl_root, $required) = @_;
    my %path = (
        upstream => File::Spec->catfile($perl_root, qw(lib unicore Name.pl)),
        imported => File::Spec->catfile($source_root, qw(src main perl lib unicore Name.pl)),
    );
    my %result;
    for my $name (sort keys %path) {
        if (-f $path{$name}) {
            $result{$name} = { %{file_identity($path{$name}, "$name Name.pl")},
                present => JSON::PP::true };
        } else {
            die "$name unicore/Name.pl is missing after sync\n" if $required;
            $result{$name} = { path => File::Spec->rel2abs($path{$name}),
                present => JSON::PP::false, sha256 => undef };
        }
    }
    return \%result;
}

sub checkout_identity {
    my ($root, $label, $policy) = @_;
    my $commit = git_line($root, 'rev-parse', 'HEAD');
    die "$label checkout did not report a full SHA\n" unless $commit =~ /\A[0-9a-f]{40}\z/;
    my $branch = git_line($root, 'symbolic-ref', '--quiet', '--short', 'HEAD');
    die "$label checkout is detached\n" unless length $branch;
    my $tracked = git_capture($root, 'status', '--porcelain', '--untracked-files=no');
    my $all = git_capture($root, 'status', '--porcelain=v1', '-z',
        '--untracked-files=all');
    my @untracked = map { substr($_, 3) }
        grep { substr($_, 0, 3) eq '?? ' } split /\0/, $all;
    my @allowed = $policy eq 'perl5'
        ? grep { $_ eq 'lib/unicore/Name.pl' } @untracked : ();
    my @unexpected = $policy eq 'perl5'
        ? grep { $_ ne 'lib/unicore/Name.pl' } @untracked : @untracked;
    my $acceptance_clean = !length($tracked) && !@unexpected;
    return { path => $root, commit => $commit, branch => $branch,
        tracked_clean => length($tracked) ? JSON::PP::false : JSON::PP::true,
        clean => length($all) ? JSON::PP::false : JSON::PP::true,
        acceptance_clean => $acceptance_clean ? JSON::PP::true : JSON::PP::false,
        untracked_paths => \@untracked,
        allowed_generated_untracked => \@allowed,
        unexpected_untracked => \@unexpected,
        status_sha256 => sha256_hex($all) };
}

sub git_capture {
    my ($root, @args) = @_;
    return capture_command([$executable{git}{path}, '-C', $root, @args], 'git');
}
sub git_line { my $value = git_capture(@_); $value =~ s/\s+\z//; return $value }

sub capture_command {
    my ($argv, $label) = @_;
    my $capture = "$output.capture-$$-" . ++$capture_sequence;
    my ($value, $error);
    eval {
        my $bound = $option{timeout} < 60 ? $option{timeout} : 60;
        my $run = run_bounded($argv, $capture, $bound, {});
        validate_run($run, $capture);
        $value = read_bounded($capture, 4 * 1024 * 1024, "$label output");
        1;
    } or $error = $@;
    unlink $capture if -e $capture || -l $capture;
    die $error if defined $error && length $error;
    return $value;
}

sub run_bounded {
    my ($argv, $log, $timeout, $environment) = @_;
    my $started = time();
    my $pid = fork();
    die "Cannot fork sync command: $!\n" unless defined $pid;
    if ($pid == 0) {
        eval { POSIX::setpgid(0, 0) };
        open STDOUT, '>:raw', $log or die "Cannot create $log: $!\n";
        chmod 0600, $log;
        open STDERR, '>&', STDOUT or die "Cannot redirect stderr: $!\n";
        for my $name (keys %$environment) {
            defined $environment->{$name} ? ($ENV{$name} = $environment->{$name}) : delete $ENV{$name};
        }
        exec { $argv->[0] } @$argv;
        die "Cannot execute $argv->[0]: $!\n";
    }
    eval { POSIX::setpgid($pid, $pid) };
    my $deadline = $started + $timeout;
    while (1) {
        my $waited = waitpid($pid, WNOHANG);
        if ($waited == $pid) {
            return { exit_code => $? >> 8, signal => $? & 127,
                timeout => 0, duration_seconds => time() - $started };
        }
        die "waitpid failed for sync command: $!\n" if $waited == -1;
        if (time() >= $deadline) {
            kill 'TERM', -$pid;
            kill 'TERM', $pid;
            sleep 0.2;
            if (waitpid($pid, WNOHANG) == 0) {
                kill 'KILL', -$pid;
                kill 'KILL', $pid;
            }
            waitpid($pid, 0);
            die "Sync command timed out after ${timeout}s; raw log: $log\n";
        }
        sleep 0.02;
    }
}

sub validate_run {
    my ($run, $log) = @_;
    die "Sync command terminated by signal $run->{signal}; raw log: $log\n" if $run->{signal};
    die "Sync command exited with status $run->{exit_code}; raw log: $log\n" if $run->{exit_code};
}

sub temporary_paths {
    my ($target) = @_;
    my $suffix = join('-', $$, int(time() * 1_000_000), int(rand(1_000_000)));
    return ("$target.log-$suffix", "$target.stage-$suffix");
}

sub write_stage {
    my ($path, $bytes) = @_;
    sysopen my $fh, $path, O_WRONLY | O_CREAT | O_EXCL, 0600
        or die "Cannot create private output staging file: $!\n";
    print {$fh} $bytes or die "Cannot write output staging file: $!\n";
    close $fh or die "Cannot close output staging file: $!\n";
}

sub file_identity {
    my ($path, $label) = @_;
    my $absolute = abs_path($path);
    die "$label is missing or not a regular file: $path\n"
        unless defined $absolute && -f $absolute;
    return { path => $absolute, sha256 => sha256_file($absolute) };
}

sub executable_identity {
    my ($path, $label) = @_;
    my $identity = file_identity($path, "$label executable");
    die "$label executable is not executable\n" unless -x $identity->{path};
    return $identity;
}

sub verify_file_identities {
    my ($files, $executables) = @_;
    for my $group ($files, $executables) {
        for my $name (keys %$group) {
            die "$name input mutated during evidence capture\n"
                unless -f $group->{$name}{path}
                    && sha256_file($group->{$name}{path}) eq $group->{$name}{sha256};
        }
    }
}

sub read_bounded {
    my ($path, $limit, $label) = @_;
    open my $fh, '<:raw', $path or die "Cannot read $label $path: $!\n";
    my $bytes = '';
    while (1) {
        my $read = read $fh, my $chunk, 65536;
        die "Cannot read $label $path: $!\n" unless defined $read;
        last unless $read;
        $bytes .= $chunk;
        die "$label exceeds bounded size of $limit bytes\n" if length($bytes) > $limit;
    }
    close $fh or die "Cannot close $label $path: $!\n";
    return $bytes;
}

sub sha256_file {
    my ($path) = @_;
    open my $fh, '<:raw', $path or die "Cannot hash $path: $!\n";
    my $digest = Digest::SHA->new(256); $digest->addfile($fh);
    close $fh or die "Cannot close $path: $!\n";
    return $digest->hexdigest;
}

sub safe_relative {
    my ($path, $label) = @_;
    die "$label is unsafe\n" if !defined($path) || !length($path)
        || File::Spec->file_name_is_absolute($path) || $path =~ /[\r\n\0]/;
    my @parts = split m{[\\/]}, $path, -1;
    die "$label is unsafe\n" if grep { $_ eq '' || $_ eq '.' || $_ eq '..' } @parts;
    return join '/', @parts;
}

sub canonical_directory {
    my ($path, $label) = @_;
    die "$label must be absolute\n" unless File::Spec->file_name_is_absolute($path);
    my $absolute = abs_path($path);
    die "$label is missing or not a directory\n" unless defined $absolute && -d $absolute;
    return $absolute;
}

sub absolute_output {
    my ($path) = @_;
    die "--output must be absolute\n" unless File::Spec->file_name_is_absolute($path);
    return File::Spec->canonpath($path);
}

sub unsafe_output {
    my ($path) = @_;
    return 1 if $path eq File::Spec->rootdir;
    my $home = defined $ENV{HOME} ? File::Spec->canonpath($ENV{HOME}) : '';
    return 1 if length($home) && $path eq $home;
    return 0;
}

sub path_is_inside {
    my ($path, $directory) = @_;
    my $relative = File::Spec->abs2rel($path, $directory);
    return $relative ne File::Spec->updir
        && $relative !~ /^\.\.(?:[\\\/]|\z)/;
}

sub normalized_repository {
    my ($value) = @_;
    $value =~ s{\Agit\@github\.com:}{https://github.com/}i;
    $value =~ s{\Assh://git\@github\.com/}{https://github.com/}i;
    $value =~ s{/+\z}{}; $value =~ s{\.git\z}{};
    return $value;
}

sub sanitize_environment {
    delete @ENV{qw(MAKEFLAGS MFLAGS MAKELEVEL GNUMAKEFLAGS FILTER
        PERL5OPT PERL5LIB PERLLIB PERL_LOCAL_LIB_ROOT PERL_MM_OPT PERL_MB_OPT
        GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY
        GIT_ALTERNATE_OBJECT_DIRECTORIES GIT_COMMON_DIR GIT_CONFIG
        GIT_CONFIG_SYSTEM GIT_CONFIG_GLOBAL GIT_CONFIG_NOSYSTEM
        GIT_CEILING_DIRECTORIES GIT_DISCOVERY_ACROSS_FILESYSTEM
        GIT_SSH GIT_SSH_COMMAND GIT_PROXY_COMMAND GIT_ALLOW_PROTOCOL)};
    delete $ENV{$_} for grep { /\AGIT_CONFIG_(?:COUNT|KEY_\d+|VALUE_\d+)\z/ } keys %ENV;
}

sub canonical { JSON::PP->new->canonical->utf8->encode($_[0]) }

sub usage {
    my ($status) = @_;
    print <<'USAGE';
Usage: perl dev/tools/run_phase36_perl5_sync_evidence.pl OPTIONS
  --source-root PATH             canonical clean PerlOnJava checkout
  --perl5-root PATH              canonical Perl upstream checkout
  --perl PATH                    canonical system Perl executable
  --git PATH                     canonical Git executable
  --make PATH                    canonical Make executable
  --repository URL               expected upstream repository
  --expected-source-commit SHA   exact pre-freeze source commit
  --output PATH                  new evidence JSON outside both checkouts
  --timeout SECONDS              hard command timeout (default: 1800)
USAGE
    exit $status;
}
