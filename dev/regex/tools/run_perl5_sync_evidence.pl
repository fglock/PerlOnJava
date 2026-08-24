#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path);
use Digest::SHA qw(sha256_hex);
use Fcntl qw(F_GETFL F_SETFL O_CREAT O_EXCL O_NONBLOCK O_WRONLY);
use File::Basename qw(basename dirname);
use File::Spec;
use Getopt::Long qw(GetOptions);
use IO::Select;
use JSON::PP;
use POSIX qw(WNOHANG);
use Time::HiRes qw(sleep time);

my $MAX_LOG_BYTES = 16 * 1024 * 1024;
my $MAX_CAPTURE_BYTES = 4 * 1024 * 1024;
my $MAX_STAGE_BYTES = 32 * 1024 * 1024;
my %option = (timeout => 1800);
my $capture_sequence = 0;
my $help;
GetOptions(
    'source-root=s' => \$option{source_root},
    'perl5-root=s' => \$option{perl5_root},
    'perl=s' => \$option{perl},
    'git=s' => \$option{git},
    'make=s' => \$option{make},
    'rsync=s' => \$option{rsync},
    'patch=s' => \$option{patch},
    'repository=s' => \$option{repository},
    'expected-source-commit=s' => \$option{expected_source_commit},
    'output=s' => \$option{output},
    'timeout=i' => \$option{timeout},
    'help' => \$help,
) or usage(2);
usage(0) if $help;
usage(2) if @ARGV;
for my $name (qw(source_root perl5_root perl git make rsync patch repository
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
} qw(perl git make rsync patch);
die "Perl executable path is unsafe for Make recipe shell expansion\n"
    unless make_shell_safe($executable{perl}{path});
my ($output, $parent) = confined_output($option{output});
my @parent_stat = stat $parent;
die "Cannot identify resolved output parent\n" unless @parent_stat;
my ($parent_device, $parent_inode) = @parent_stat[0, 1];
die "Unsafe output path: $output\n" if unsafe_output($output);
die "Refusing to overwrite output $output\n" if -e $output || -l $output;
die "Output must be outside the source and perl5 worktrees\n"
    if path_is_inside($parent, $source) || path_is_inside($parent, $perl5);
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
my $config = config_manifest($input_file{config}{path}, $source);
my $protected_before = $config->{protected_targets};
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

my ($tool_directory, $trusted_path) = trusted_tool_path($parent, \%executable);
my ($log_path, $stage_path) = temporary_paths($output);
my $published = 0;
my $ok = eval {
    my @argv = ($executable{make}{path}, '-C', $source,
        "PERL=$executable{perl}{path}", 'perl5-sync-check');
    my $command_environment = {
        PERL5_REPOSITORY => $option{repository},
        FILTER => undef,
        PATH => $trusted_path,
        LC_ALL => 'C', LANG => 'C',
    };
    my $run = run_bounded(\@argv, $log_path, $option{timeout},
        $command_environment, $MAX_LOG_BYTES, 'sync log');
    validate_run($run, $log_path);
    my $log = $run->{output};
    my $markers = validate_log($log, $remote_before->{tip}, $config);

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
    my $config_after = config_manifest($input_file{config}{path}, $source);
    my $protected_after = $config_after->{protected_targets};
    die "Sync config manifest changed during capture\n"
        unless canonical($config_after) eq canonical($config);
    die "Protected targets mutated during sync evidence capture\n"
        unless canonical($protected_after) eq canonical($protected_before);
    my $name_after = name_manifest($source, $perl5, 1);
    die "Upstream and imported unicore/Name.pl differ after sync\n"
        unless $name_after->{upstream}{sha256} eq $name_after->{imported}{sha256};
    verify_file_identities(\%input_file, \%executable);

    my $document = {
        schema_version => 1,
        kind => 'regex_implementation-perl5-sync-evidence',
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
    verify_output_parent();
    die "Refusing publication race at $output\n" unless link $stage_path, $output;
    $published = 1;
    my $source_published = checkout_identity($source, 'source', 'strict');
    my $perl_published = checkout_identity($perl5, 'perl5', 'perl5');
    unless (canonical($source_published) eq canonical($source_after)
            && canonical($perl_published) eq canonical($perl_after)) {
        unlink $output or die "Checkout changed after publication and cannot remove $output: $!\n";
        $published = 0;
        die "Source or Perl5 checkout changed after exclusive publication\n";
    }
    1;
};
my $error = $@;
unlink $stage_path if defined $stage_path && (-e $stage_path || -l $stage_path);
unlink $log_path if defined $log_path && (-e $log_path || -l $log_path);
remove_trusted_tool_path($tool_directory, \%executable)
    if defined $tool_directory;
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
    my ($log, $tip, $config) = @_;
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
        unless @full == 2
            && $full[0] == $config->{import_count}
            && $full[1] == $config->{import_count};
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
        unless @protected == 2
            && $protected[0] == $config->{protected_count}
            && $protected[1] == $config->{protected_count};
    return { full_manifest_count => 0 + $full[0], pass_count => 2,
        successful_per_pass => [map { 0 + $_ } @success],
        errors_per_pass => [map { 0 + $_ } @errors],
        protected_count_per_pass => [map { 0 + $_ } @protected],
        second_pass_seen => JSON::PP::true, idempotence_verified => JSON::PP::true };
}

sub config_manifest {
    my ($config, $root) = @_;
    my $text = read_bounded($config, 4 * 1024 * 1024, 'sync config');
    my (@rows, $source_path, $target, $protected);
    for my $line (split /\n/, $text) {
        if ($line =~ /^\s*-\s+source:\s*(.*?)\s*$/) {
            push @rows, [$source_path, $target, $protected]
                if defined $source_path;
            ($source_path, $target, $protected) = (yaml_scalar($1), undef, 0);
        } elsif ($line =~ /^\s+target:\s*(.*?)\s*$/) {
            die "Sync config import repeats target\n" if defined $target;
            $target = yaml_scalar($1);
        } elsif ($line =~ /^\s+protected:\s*(.*?)\s*$/) {
            my $value = yaml_scalar($1);
            die "Sync config has invalid protected value\n"
                unless $value =~ /\A(?:true|yes|1|false|no|0)\z/i;
            $protected = $value =~ /\A(?:true|yes|1)\z/i ? 1 : 0;
        }
    }
    push @rows, [$source_path, $target, $protected] if defined $source_path;
    die "Sync config contains no imports\n" unless @rows;
    for my $row (@rows) {
        die "Sync config import is missing a target\n"
            unless defined $row->[1] && length $row->[1];
        safe_relative($row->[0], 'import source');
        safe_relative($row->[1], 'import target');
    }
    my @manifest;
    for my $row (@rows) {
        next unless $row->[2];
        my $relative = safe_relative($row->[1], 'protected target');
        my $path = File::Spec->catfile($root, split m{/}, $relative);
        die "Protected target is missing or not a regular file: $relative\n" unless -f $path;
        push @manifest, { path => $relative, sha256 => sha256_file($path) };
    }
    my %seen;
    die "Sync config repeats a protected target\n"
        if grep { $seen{$_->{path}}++ } @manifest;
    return {
        import_count => 0 + @rows,
        protected_count => 0 + @manifest,
        protected_targets => \@manifest,
    };
}

sub yaml_scalar {
    my ($value) = @_;
    die "Sync config contains an empty scalar\n"
        unless defined $value && length $value;
    if ($value =~ /\A(['"])(.*)\1\z/) {
        $value = $2;
    } elsif ($value =~ /['"]/) {
        die "Sync config contains malformed quoting\n";
    }
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
        my $run = run_bounded($argv, $capture, $bound, {},
            $MAX_CAPTURE_BYTES, "$label output");
        validate_run($run, $capture);
        $value = $run->{output};
        1;
    } or $error = $@;
    unlink $capture if -e $capture || -l $capture;
    die $error if defined $error && length $error;
    return $value;
}

sub run_bounded {
    my ($argv, $log, $timeout, $environment, $limit, $label) = @_;
    my $started = time();
    my $caught_signal;
    local $SIG{INT} = sub { $caught_signal = 'INT' };
    local $SIG{TERM} = sub { $caught_signal = 'TERM' };
    local $SIG{HUP} = sub { $caught_signal = 'HUP' };
    local $SIG{QUIT} = sub { $caught_signal = 'QUIT' };
    verify_output_parent();
    sysopen my $log_fh, $log, O_WRONLY | O_CREAT | O_EXCL, 0600
        or die "Cannot exclusively create $label $log: $!\n";
    pipe my $reader, my $writer or die "Cannot create command output pipe: $!\n";
    my $flags = fcntl($reader, F_GETFL, 0);
    die "Cannot inspect command pipe flags: $!\n" unless defined $flags;
    fcntl($reader, F_SETFL, $flags | O_NONBLOCK)
        or die "Cannot make command pipe nonblocking: $!\n";
    my $pid = fork();
    die "Cannot fork sync command: $!\n" unless defined $pid;
    if ($pid == 0) {
        $SIG{$_} = 'DEFAULT' for qw(INT TERM HUP QUIT);
        close $reader;
        close $log_fh;
        POSIX::setpgid(0, 0) == 0
            or die "Cannot create isolated command process group: $!\n";
        open STDOUT, '>&', $writer or die "Cannot redirect stdout: $!\n";
        open STDERR, '>&', STDOUT or die "Cannot redirect stderr: $!\n";
        close $writer;
        for my $name (keys %$environment) {
            defined $environment->{$name} ? ($ENV{$name} = $environment->{$name}) : delete $ENV{$name};
        }
        exec { $argv->[0] } @$argv;
        die "Cannot execute $argv->[0]: $!\n";
    }
    close $writer;
    unless (POSIX::setpgid($pid, $pid) == 0 || $!{EACCES} || $!{ESRCH}) {
        my $error = $!;
        kill 'TERM', -$pid; kill 'TERM', $pid;
        sleep 0.05;
        kill 'KILL', -$pid; kill 'KILL', $pid;
        waitpid($pid, 0);
        close $reader; close $log_fh;
        die "Cannot establish command process group: $error\n";
    }
    my $selector = IO::Select->new($reader);
    my $deadline = $started + $timeout;
    my ($status, $eof, $written, $failure) = (undef, 0, 0, undef);
    my $captured = '';
    while (!defined($status) || !$eof) {
        for my $ready ($selector->can_read(0.02)) {
            while (1) {
                my $read = sysread $ready, my $chunk, 65536;
                if (defined $read && $read) {
                    if ($written + $read > $limit) {
                        $failure = "$label exceeds bounded size of $limit bytes";
                        last;
                    }
                    write_all($log_fh, $chunk, $label);
                    $captured .= $chunk;
                    $written += $read;
                    next;
                }
                if (defined $read) {
                    $eof = 1;
                    $selector->remove($ready);
                } elsif (!$!{EAGAIN} && !$!{EWOULDBLOCK}) {
                    $failure = "Cannot read $label command pipe: $!";
                }
                last;
            }
        }
        if (!defined $status) {
            my $waited = waitpid($pid, WNOHANG);
            if ($waited == $pid) {
                $status = $?;
                terminate_process_group($pid);
            } elsif ($waited == -1) {
                $failure = "waitpid failed for sync command: $!";
            }
        }
        if (!defined($status) && time() >= $deadline) {
            $failure = "Sync command timed out after ${timeout}s; raw log: $log";
        }
        if (!defined($status) && defined $caught_signal) {
            $failure = "Sync command interrupted by $caught_signal; raw log: $log";
        }
        if ($failure && !defined $status) {
            terminate_process_group($pid);
            waitpid($pid, 0);
            $status = $?;
        }
        if ($failure) {
            terminate_process_group($pid);
            last if defined $status;
        }
    }
    close $reader or die "Cannot close command output pipe: $!\n";
    close $log_fh or die "Cannot close $label $log: $!\n";
    die "$failure\n" if $failure;
    return { exit_code => $status >> 8, signal => $status & 127,
        timeout => 0, duration_seconds => time() - $started,
        output => $captured };
}

sub terminate_process_group {
    my ($pid) = @_;
    return unless kill 0, -$pid;
    kill 'TERM', -$pid;
    my $deadline = time() + 0.25;
    sleep 0.01 while time() < $deadline && kill(0, -$pid);
    kill 'KILL', -$pid if kill 0, -$pid;
    $deadline = time() + 1;
    sleep 0.01 while time() < $deadline && kill(0, -$pid);
    die "Cannot terminate complete command process group $pid\n"
        if kill 0, -$pid;
}

sub write_all {
    my ($fh, $bytes, $label) = @_;
    my $offset = 0;
    while ($offset < length $bytes) {
        my $written = syswrite $fh, $bytes, length($bytes) - $offset, $offset;
        die "Cannot write $label: $!\n" unless defined $written && $written;
        $offset += $written;
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
    die "Evidence stage exceeds bounded size of $MAX_STAGE_BYTES bytes\n"
        if length($bytes) > $MAX_STAGE_BYTES;
    verify_output_parent();
    sysopen my $fh, $path, O_WRONLY | O_CREAT | O_EXCL, 0600
        or die "Cannot create private output staging file: $!\n";
    write_all($fh, $bytes, 'output staging file');
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

sub confined_output {
    my ($path) = @_;
    die "--output must be absolute\n" unless File::Spec->file_name_is_absolute($path);
    my $name = basename($path);
    die "Unsafe output filename\n"
        if !length($name) || $name eq '.' || $name eq '..' || $name =~ /[\/\\\r\n\0]/;
    my $requested_parent = dirname(File::Spec->canonpath($path));
    my $resolved_parent = abs_path($requested_parent);
    die "Output parent is missing or not a directory: $requested_parent\n"
        unless defined $resolved_parent && -d $resolved_parent;
    my $output = File::Spec->catfile($resolved_parent, $name);
    die "Resolved output escaped its parent\n"
        unless dirname($output) eq $resolved_parent;
    return ($output, $resolved_parent);
}

sub make_shell_safe {
    my ($path) = @_;
    return $path =~ /\A[A-Za-z0-9_\.\/+-]+\z/;
}

sub trusted_tool_path {
    my ($parent, $executables) = @_;
    verify_output_parent();
    my $suffix = join('-', $$, int(time() * 1_000_000), int(rand(1_000_000)));
    my $directory = File::Spec->catdir($parent, ".a236-tools-$suffix");
    mkdir $directory, 0700
        or die "Cannot exclusively create trusted tool directory: $!\n";
    my $ok = eval {
        for my $name (sort keys %$executables) {
            my $alias = File::Spec->catfile($directory, $name);
            symlink $executables->{$name}{path}, $alias
                or die "Cannot bind trusted $name executable: $!\n";
        }
        1;
    };
    my $error = $@;
    unless ($ok) {
        unlink File::Spec->catfile($directory, $_) for keys %$executables;
        rmdir $directory;
        die $error;
    }
    return ($directory, $directory);
}

sub remove_trusted_tool_path {
    my ($directory, $executables) = @_;
    for my $name (sort keys %$executables) {
        my $alias = File::Spec->catfile($directory, $name);
        unlink $alias or die "Cannot remove trusted $name alias: $!\n"
            if -e $alias || -l $alias;
    }
    rmdir $directory or die "Cannot remove trusted tool directory: $!\n";
}

sub verify_output_parent {
    my $resolved = abs_path($parent);
    my @current = stat $parent;
    die "Resolved output parent changed during evidence capture\n"
        unless defined $resolved && $resolved eq $parent && @current
            && $current[0] == $parent_device && $current[1] == $parent_inode;
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
Usage: perl dev/regex/tools/run_perl5_sync_evidence.pl OPTIONS
  --source-root PATH             canonical clean PerlOnJava checkout
  --perl5-root PATH              canonical Perl upstream checkout
  --perl PATH                    canonical system Perl executable
  --git PATH                     canonical Git executable
  --make PATH                    canonical Make executable
  --rsync PATH                   canonical rsync executable used by sync.pl
  --patch PATH                   canonical patch executable used by sync.pl
  --repository URL               expected upstream repository
  --expected-source-commit SHA   exact pre-freeze source commit
  --output PATH                  new evidence JSON outside both checkouts
  --timeout SECONDS              hard command timeout (default: 1800)
USAGE
    exit $status;
}
