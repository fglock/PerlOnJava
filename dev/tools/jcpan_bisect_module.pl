#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path getcwd);
use File::Basename qw(dirname);
use File::Path qw(make_path);
use File::Spec;
use Getopt::Long qw(GetOptions);
use IO::Select;
use POSIX qw(strftime WNOHANG);
use Time::HiRes qw(sleep time);

my %opt = (
    bad              => undef,
    build_cmd        => 'make',
    build_failure    => 'bad',
    build_timeout    => 1800,
    cache            => 1,
    first_parent     => 1,
    isolate_home     => 0,
    jobs             => 1,
    no_build         => 0,
    probe            => 0,
    reuse_worktree   => 0,
    share_sources    => 1,
    timeout          => 7200,
    timeout_result   => 'bad',
    unknown_result   => 'bad',
    verify_endpoints => 0,
);

GetOptions(
    'module=s'           => \$opt{module},
    'good=s'             => \$opt{good},
    'bad=s'              => \$opt{bad},
    'timeout=i'          => \$opt{timeout},
    'build-timeout=i'    => \$opt{build_timeout},
    'build-cmd=s'        => \$opt{build_cmd},
    'no-build'           => \$opt{no_build},
    'jobs=i'             => \$opt{jobs},
    'worktree=s'         => \$opt{worktree},
    'reuse-worktree'     => \$opt{reuse_worktree},
    'log-dir=s'          => \$opt{log_dir},
    'state-dir=s'        => \$opt{state_dir},
    'pass-dat=s'         => \$opt{pass_dat},
    'first-parent!'      => \$opt{first_parent},
    'all-commits'        => sub { $opt{first_parent} = 0 },
    'verify-endpoints'   => \$opt{verify_endpoints},
    'isolate-home!'      => \$opt{isolate_home},
    'share-sources!'     => \$opt{share_sources},
    'cache!'             => \$opt{cache},
    'timeout-result=s'   => \$opt{timeout_result},
    'build-failure=s'    => \$opt{build_failure},
    'unknown-result=s'   => \$opt{unknown_result},
    'probe'              => \$opt{probe},
    'help|h'             => \$opt{help},
) or die usage();

if ($opt{help}) {
    print usage();
    exit 0;
}

die "--module is required\n\n" . usage() unless defined $opt{module} && length $opt{module};
validate_verdict_option('timeout-result', $opt{timeout_result});
validate_verdict_option('build-failure',  $opt{build_failure});
validate_verdict_option('unknown-result', $opt{unknown_result});

my $script_path = abs_path($0) || $0;

if ($opt{probe}) {
    exit run_probe(\%opt);
}

exit run_driver(\%opt, $script_path);

sub run_driver {
    my ($opt, $script_path) = @_;

    my $repo = git_capture('.', qw(rev-parse --show-toplevel));
    chomp $repo;
    die "Could not determine repository root\n" unless length $repo;

    my $safe_module = safe_name($opt->{module});
    my $stamp       = strftime('%Y%m%d-%H%M%S', localtime);

    $opt->{bad} ||= default_bad_ref($repo);
    $opt->{pass_dat} ||= File::Spec->catfile(
        $repo, 'dev', 'cpan-reports', 'cpan-compatibility-pass.dat'
    );
    $opt->{good} ||= latest_pass_commit($opt->{pass_dat}, $opt->{module});
    $opt->{worktree} ||= File::Spec->catdir(
        '/tmp', "perlonjava-jcpan-bisect-$safe_module-$stamp"
    );
    $opt->{log_dir} ||= File::Spec->catdir(
        '/tmp', "perlonjava-jcpan-bisect-logs-$safe_module-$stamp"
    );
    $opt->{state_dir} ||= File::Spec->catdir(
        '/tmp', "perlonjava-jcpan-bisect-state-$safe_module"
    );

    make_path($opt->{log_dir});
    make_path($opt->{state_dir});

    print "Module:      $opt->{module}\n";
    print "Good:        $opt->{good}\n";
    print "Bad:         $opt->{bad}\n";
    print "Worktree:    $opt->{worktree}\n";
    print "Logs:        $opt->{log_dir}\n";
    print "State:       $opt->{state_dir}\n";
    print "Timeout:     $opt->{timeout}s\n";
    print "Build:       " . ($opt->{no_build} ? '(disabled)' : $opt->{build_cmd}) . "\n";
    print "Scope:       " . ($opt->{first_parent} ? 'first-parent PR merges' : 'all commits') . "\n";
    print "Isolation:   " . ($opt->{isolate_home} ? 'isolated HOME' : 'current HOME') . "\n";
    print "\n";

    prepare_worktree($repo, $opt->{worktree}, $opt->{bad}, $opt->{reuse_worktree});

    if ($opt->{verify_endpoints}) {
        verify_endpoint($opt, $script_path, $opt->{worktree}, $opt->{good}, 'good');
        verify_endpoint($opt, $script_path, $opt->{worktree}, $opt->{bad},  'bad');
    }

    # Harmless when no bisect is active, useful when --reuse-worktree points at
    # a previous interrupted run. Ignore failures because old Git versions may
    # return non-zero for "not currently bisecting".
    system 'git', '-C', $opt->{worktree}, 'bisect', 'reset';

    my @start = ('git', '-C', $opt->{worktree}, 'bisect', 'start');
    push @start, '--first-parent' if $opt->{first_parent};
    push @start, $opt->{bad}, $opt->{good};
    run_or_die(@start);

    my @probe = (
        $^X, $script_path,
        '--probe',
        '--module',        $opt->{module},
        '--timeout',       $opt->{timeout},
        '--build-timeout', $opt->{build_timeout},
        '--build-cmd',     $opt->{build_cmd},
        '--jobs',          $opt->{jobs},
        '--log-dir',       $opt->{log_dir},
        '--state-dir',     $opt->{state_dir},
        '--timeout-result', $opt->{timeout_result},
        '--build-failure',  $opt->{build_failure},
        '--unknown-result', $opt->{unknown_result},
    );
    push @probe, '--no-build'       if $opt->{no_build};
    push @probe, '--first-parent'   if $opt->{first_parent};
    push @probe, '--isolate-home'   if $opt->{isolate_home};
    push @probe, '--no-isolate-home' unless $opt->{isolate_home};
    push @probe, '--share-sources'  if $opt->{share_sources};
    push @probe, '--no-share-sources' unless $opt->{share_sources};
    push @probe, '--cache'          if $opt->{cache};
    push @probe, '--no-cache'       unless $opt->{cache};

    my @bisect = ('git', '-C', $opt->{worktree}, 'bisect', 'run', @probe);
    print "Starting git bisect run...\n\n";
    my $rc = system @bisect;
    my $exit = exit_code($rc);

    my $bisect_log = File::Spec->catfile($opt->{log_dir}, 'git-bisect.log');
    eval {
        my $log = git_capture($opt->{worktree}, qw(bisect log));
        write_file($bisect_log, $log);
    };
    warn "Could not write bisect log: $@\n" if $@;

    print "\nBisect finished with exit code $exit\n";
    print "Current worktree HEAD: " . git_capture($opt->{worktree}, qw(rev-parse --short HEAD));
    print "Saved git bisect log: $bisect_log\n";
    print "Inspect or reset the bisect worktree manually:\n";
    print "  git -C $opt->{worktree} bisect reset\n";
    print "  git worktree remove $opt->{worktree}\n";

    return $exit;
}

sub run_probe {
    my ($opt) = @_;

    my $cwd    = getcwd();
    my $commit = git_capture($cwd, qw(rev-parse --short HEAD));
    chomp $commit;

    my $safe_module = safe_name($opt->{module});
    my $log_dir = $opt->{log_dir} || File::Spec->catdir('/tmp', "perlonjava-jcpan-bisect-logs-$safe_module");
    my $state_dir = $opt->{state_dir} || File::Spec->catdir('/tmp', "perlonjava-jcpan-bisect-state-$safe_module");
    my $module_log_dir = File::Spec->catdir($log_dir, $safe_module);
    my $result_dir = File::Spec->catdir($log_dir, 'results');
    make_path($module_log_dir);
    make_path($result_dir);

    my $status_file = File::Spec->catfile($result_dir, "$commit.status");
    if ($opt->{cache} && -f $status_file) {
        my $cached = read_file($status_file);
        if ($cached =~ /^(good|bad|skip)\b/) {
            print "cached $1 for $commit: $cached";
            return verdict_exit($1);
        }
    }

    my $log_file = File::Spec->catfile($module_log_dir, "$commit.log");
    open my $log_fh, '>', $log_file or die "Cannot write $log_file: $!\n";
    print $log_fh "module=$opt->{module}\n";
    print $log_fh "commit=$commit\n";
    print $log_fh "cwd=$cwd\n";
    print $log_fh "started=" . scalar(localtime) . "\n\n";

    if (!$opt->{no_build}) {
        print "[$commit] build: $opt->{build_cmd}\n";
        print $log_fh "========== build ==========\n";
        my ($build_exit, $build_timed_out) = run_command(
            ['/bin/sh', '-c', $opt->{build_cmd}],
            $opt->{build_timeout},
            cwd    => $cwd,
            log_fh => $log_fh,
        );
        print $log_fh "\nbuild_exit=$build_exit build_timed_out=$build_timed_out\n\n";

        if ($build_timed_out || $build_exit != 0) {
            my $verdict = $opt->{build_failure};
            cache_verdict($status_file, $verdict, "build failed exit=$build_exit timed_out=$build_timed_out\n");
            print "[$commit] $verdict: build failed (exit=$build_exit timed_out=$build_timed_out, log=$log_file)\n";
            return verdict_exit($verdict);
        }
    }

    my %env = probe_env($opt, $state_dir);
    my @cmd = ('./jcpan');
    push @cmd, '--jobs', $opt->{jobs} if $opt->{jobs} && $opt->{jobs} > 1;
    push @cmd, '-t', $opt->{module};

    print "[$commit] test: @cmd (timeout $opt->{timeout}s)\n";
    print $log_fh "========== jcpan ==========\n";
    my ($test_exit, $test_timed_out, $output) = run_command(
        \@cmd,
        $opt->{timeout},
        cwd    => $cwd,
        env    => \%env,
        log_fh => $log_fh,
    );
    print $log_fh "\njcpan_exit=$test_exit jcpan_timed_out=$test_timed_out\n";
    print $log_fh "finished=" . scalar(localtime) . "\n";
    close $log_fh;

    my ($verdict, $detail) = classify_jcpan_result(
        $opt->{module}, $output, $test_exit, $test_timed_out, $opt
    );
    cache_verdict($status_file, $verdict, "$detail log=$log_file\n");
    print "[$commit] $verdict: $detail (log=$log_file)\n";
    return verdict_exit($verdict);
}

sub prepare_worktree {
    my ($repo, $worktree, $bad, $reuse) = @_;

    if (-e $worktree) {
        die "Worktree path exists: $worktree\nUse --reuse-worktree if this is intentional.\n"
            unless $reuse;
        return;
    }

    run_or_die('git', '-C', $repo, 'worktree', 'add', '--detach', $worktree, $bad);
}

sub verify_endpoint {
    my ($opt, $script_path, $worktree, $rev, $expected) = @_;

    print "Verifying $expected endpoint $rev...\n";
    run_or_die('git', '-C', $worktree, 'checkout', '--detach', $rev);

    my @cmd = (
        $^X, $script_path,
        '--probe',
        '--module', $opt->{module},
        '--timeout', $opt->{timeout},
        '--build-timeout', $opt->{build_timeout},
        '--build-cmd', $opt->{build_cmd},
        '--jobs', $opt->{jobs},
        '--log-dir', $opt->{log_dir},
        '--state-dir', $opt->{state_dir},
        '--timeout-result', $opt->{timeout_result},
        '--build-failure', $opt->{build_failure},
        '--unknown-result', $opt->{unknown_result},
    );
    push @cmd, '--no-build' if $opt->{no_build};
    push @cmd, $opt->{isolate_home} ? '--isolate-home' : '--no-isolate-home';
    push @cmd, $opt->{share_sources} ? '--share-sources' : '--no-share-sources';
    push @cmd, $opt->{cache} ? '--cache' : '--no-cache';

    my $rc = system @cmd;
    my $got = exit_code($rc);
    my $want = $expected eq 'good' ? 0 : 1;
    die "Endpoint $rev was expected to be $expected, but probe exited $got\n"
        unless $got == $want;
}

sub probe_env {
    my ($opt, $state_dir) = @_;
    my %env = %ENV;

    return %env unless $opt->{isolate_home};

    my $home = File::Spec->catdir($state_dir, 'home');
    die "Isolated home contains whitespace, which jperl's JPERL_OPTS cannot pass safely: $home\n"
        if $home =~ /\s/;

    make_path(File::Spec->catdir($home, '.perlonjava', 'cpan'));
    if ($opt->{share_sources} && defined $ENV{HOME}) {
        my $real_sources = File::Spec->catdir($ENV{HOME}, '.perlonjava', 'cpan', 'sources');
        my $isol_sources = File::Spec->catdir($home, '.perlonjava', 'cpan', 'sources');
        if (-d $real_sources && !-e $isol_sources) {
            symlink $real_sources, $isol_sources
                or warn "Could not symlink CPAN sources into isolated home: $!\n";
        }
    }

    $env{HOME} = $home;
    my $existing = $env{JPERL_OPTS} // '';
    $env{JPERL_OPTS} = join ' ', grep { length } ($existing, "-Duser.home=$home");
    return %env;
}

sub classify_jcpan_result {
    my ($module, $output, $exit, $timed_out, $opt) = @_;

    if ($timed_out) {
        return ($opt->{timeout_result}, "timeout after $opt->{timeout}s");
    }

    my @results = parse_all_module_results($output);
    for my $r (@results) {
        next unless $r->{module} eq $module;
        if ($r->{status} eq 'PASS') {
            my $tests = defined $r->{tests} ? " tests=$r->{tests}" : '';
            return ('good', "target PASS$tests");
        }
        my $err = $r->{error} || 'target failed';
        my $tests = defined $r->{tests} ? " tests=$r->{pass_count}/$r->{tests}" : '';
        return ('bad', "$err$tests");
    }

    if ($exit != 0) {
        return ('bad', "jcpan exited $exit before target result was parsed");
    }

    if ($output =~ /Result:\s+PASS/ && $output !~ /Result:\s+FAIL/) {
        return ('good', 'jcpan exited 0 and output contains Result: PASS');
    }

    return ($opt->{unknown_result}, 'could not parse target test result');
}

sub parse_all_module_results {
    my ($output) = @_;
    my @results;
    my %seen;
    my %dist_to_mod;
    my $last_mod;

    for my $line (split /\n/, $output) {
        if ($line =~ /Running (?:test|install) for module '([^']+)'/) {
            $last_mod = $1;
        }
        if ($last_mod && $line =~ m{Configuring \S+/(\S+)\.tar\.gz}) {
            $dist_to_mod{$1} = $last_mod;
        }
    }

    my @test_blocks;
    my $cur_dist;
    my $cur_text = '';
    for my $line (split /\n/, $output) {
        if ($line =~ m{Running (?:make|Build) test for \S+/(\S+)\.tar\.gz}) {
            if ($cur_dist) {
                push @test_blocks, { dist => $cur_dist, text => $cur_text };
            }
            $cur_dist = $1;
            $cur_text = "$line\n";
        } elsif ($cur_dist) {
            $cur_text .= "$line\n";
            if ($line =~ /(?:make|Build) test -- (?:OK|NOT OK)/) {
                push @test_blocks, { dist => $cur_dist, text => $cur_text };
                $cur_dist = undef;
                $cur_text = '';
            }
        }
    }
    push @test_blocks, { dist => $cur_dist, text => $cur_text } if $cur_dist;

    for my $block (@test_blocks) {
        my $dist = $block->{dist};
        my $text = $block->{text};
        my $mod  = $dist_to_mod{$dist};

        unless ($mod) {
            ($mod = $dist) =~ s/-[\d._]+$//;
            $mod =~ s/-/::/g;
        }
        next if $seen{$mod}++;

        my %r = (
            module     => $mod,
            status     => 'UNKNOWN',
            tests      => undef,
            pass_count => undef,
            error      => '',
        );

        my $total_tests = 0;
        my $subtests_fail = 0;
        if ($text =~ /Files=\d+,\s+Tests=(\d+)/) {
            $total_tests = $1;
        }
        while ($text =~ /Failed\s+(\d+)\/(\d+)\s+subtests/g) {
            $subtests_fail += $1;
        }

        if ($text =~ /All tests successful/ || $text =~ /Result:\s+PASS/) {
            $r{status} = 'PASS';
            $r{tests} = $total_tests || undef;
            $r{pass_count} = $total_tests || undef;
            push @results, \%r;
            next;
        }

        if ($text =~ /Result:\s+FAIL/ || $text =~ /(?:make|Build) test -- NOT OK/) {
            $r{status} = 'FAIL';
            if ($total_tests > 0) {
                $r{tests} = $total_tests;
                $r{pass_count} = $total_tests > $subtests_fail
                    ? $total_tests - $subtests_fail : 0;
                $r{error} = sprintf('%d/%d subtests failed', $subtests_fail, $total_tests)
                    if $subtests_fail;
            }
            if (!$r{error}) {
                if ($text =~ /Can't locate (\S+\.pm)/m) {
                    $r{error} = "Missing: $1";
                } elsif ($text =~ /StackOverflowError/) {
                    $r{error} = 'StackOverflowError';
                } elsif ($text =~ /OutOfMemoryError/) {
                    $r{error} = 'OutOfMemoryError';
                } elsif ($text =~ /syntax error/i) {
                    $r{error} = 'Syntax error';
                } else {
                    $r{error} = 'test failed';
                }
            }
            push @results, \%r;
        }
    }

    for my $line (split /\n/, $output) {
        if ($line =~ /Running (?:test|install) for module '([^']+)'/) {
            $last_mod = $1;
        }
        if ($last_mod && !$seen{$last_mod}
            && $line =~ /(?:Makefile\.PL|Build\.PL) -- NOT OK/) {
            $seen{$last_mod}++;
            push @results, {
                module => $last_mod, status => 'FAIL',
                tests => undef, pass_count => undef, error => 'Configure failed',
            };
        }
        if ($last_mod && !$seen{$last_mod}
            && $line =~ /(?:jperl|perl) Build -- NOT OK/) {
            $seen{$last_mod}++;
            push @results, {
                module => $last_mod, status => 'FAIL',
                tests => undef, pass_count => undef, error => 'Build failed',
            };
        }
    }

    return @results;
}

sub run_command {
    my ($cmd, $timeout, %args) = @_;
    my $cwd = $args{cwd} || getcwd();
    my $env = $args{env} || {};
    my $log_fh = $args{log_fh};

    pipe(my $read, my $write) or die "pipe failed: $!\n";
    my $pid = fork();
    die "fork failed: $!\n" unless defined $pid;

    if ($pid == 0) {
        close $read;
        setpgrp(0, 0);
        chdir $cwd or die "chdir $cwd failed: $!\n";
        while (my ($key, $value) = each %$env) {
            if (defined $value) {
                $ENV{$key} = $value;
            } else {
                delete $ENV{$key};
            }
        }
        open STDOUT, '>&', $write or die "dup stdout failed: $!\n";
        open STDERR, '>&', $write or die "dup stderr failed: $!\n";
        close $write;
        exec @$cmd or do {
            print STDERR "exec failed for @$cmd: $!\n";
            exit 127;
        };
    }

    close $write;
    my $select = IO::Select->new($read);
    my $deadline = time() + $timeout;
    my $output = '';
    my $timed_out = 0;
    my $status;
    my $reaped = 0;

    while (1) {
        my $remaining = $deadline - time();
        if ($remaining <= 0) {
            $timed_out = 1;
            last;
        }

        my @ready = $select->can_read($remaining > 1 ? 1 : $remaining);
        if (@ready) {
            my $buf = '';
            my $n = sysread($read, $buf, 8192);
            if (!defined $n) {
                next if $!{EINTR};
                last;
            }
            last if $n == 0;
            $output .= $buf;
            print {$log_fh} $buf if $log_fh;
        }

        if (!$reaped) {
            my $wp = waitpid($pid, WNOHANG);
            if ($wp == $pid) {
                $status = $?;
                $reaped = 1;
            }
        }
    }

    if ($timed_out) {
        kill 'TERM', -$pid;
        for (1..10) {
            my $wp = waitpid($pid, WNOHANG);
            if ($wp == $pid) {
                $status = $?;
                $reaped = 1;
                last;
            }
            sleep 0.2;
        }
        if (!$reaped) {
            kill 'KILL', -$pid;
            waitpid($pid, 0);
            $status = $?;
            $reaped = 1;
        }
    } elsif (!$reaped) {
        waitpid($pid, 0);
        $status = $?;
    }

    # Drain any buffered data left in the pipe after the child exits.
    while (1) {
        my $buf = '';
        my $n = sysread($read, $buf, 8192);
        last unless defined $n && $n > 0;
        $output .= $buf;
        print {$log_fh} $buf if $log_fh;
    }
    close $read;

    return (exit_code($status), $timed_out ? 1 : 0, $output);
}

sub latest_pass_commit {
    my ($path, $module) = @_;
    open my $fh, '<', $path or die "Cannot read $path: $!\n";
    while (my $line = <$fh>) {
        chomp $line;
        my @f = split /\t/, $line, -1;
        next unless @f >= 8;
        next unless $f[0] eq $module && $f[1] eq 'PASS';
        return $f[7] if length $f[7];
    }
    die "No PASS row for $module in $path. Pass --good explicitly.\n";
}

sub default_bad_ref {
    my ($repo) = @_;
    for my $ref (qw(master origin/master HEAD)) {
        return $ref if ref_exists($repo, $ref);
    }
    return 'HEAD';
}

sub ref_exists {
    my ($repo, $ref) = @_;
    my $pid = open my $fh, '-|';
    die "fork failed: $!\n" unless defined $pid;
    if ($pid == 0) {
        chdir $repo or exit 2;
        open STDOUT, '>', File::Spec->devnull() or exit 2;
        open STDERR, '>', File::Spec->devnull() or exit 2;
        exec 'git', 'rev-parse', '--verify', '--quiet', $ref;
        exit 127;
    }
    close $fh;
    return $? == 0;
}

sub validate_verdict_option {
    my ($name, $value) = @_;
    die "--$name must be one of: bad, skip\n"
        unless defined $value && ($value eq 'bad' || $value eq 'skip');
}

sub verdict_exit {
    my ($verdict) = @_;
    return 0   if $verdict eq 'good';
    return 1   if $verdict eq 'bad';
    return 125 if $verdict eq 'skip';
    die "Unknown verdict: $verdict\n";
}

sub cache_verdict {
    my ($path, $verdict, $detail) = @_;
    write_file($path, "$verdict\t$detail");
}

sub safe_name {
    my ($name) = @_;
    $name =~ s/[^A-Za-z0-9_.-]+/-/g;
    return $name;
}

sub git_capture {
    my ($cwd, @args) = @_;
    my $pid = open my $fh, '-|';
    die "fork failed: $!\n" unless defined $pid;
    if ($pid == 0) {
        chdir $cwd or die "chdir $cwd failed: $!\n";
        exec 'git', @args;
        die "exec git @args failed: $!\n";
    }
    local $/;
    my $out = <$fh> // '';
    close $fh or die "git @args failed in $cwd\n";
    return $out;
}

sub run_or_die {
    my (@cmd) = @_;
    my $rc = system @cmd;
    die "Command failed (" . exit_code($rc) . "): @cmd\n" if $rc != 0;
}

sub exit_code {
    my ($status) = @_;
    return 127 unless defined $status;
    return 128 + ($status & 127) if $status & 127;
    return $status >> 8;
}

sub read_file {
    my ($path) = @_;
    open my $fh, '<', $path or die "Cannot read $path: $!\n";
    local $/;
    return <$fh> // '';
}

sub write_file {
    my ($path, $content) = @_;
    my $dir = dirname($path);
    make_path($dir) unless -d $dir;
    open my $fh, '>', $path or die "Cannot write $path: $!\n";
    print {$fh} $content;
    close $fh;
}

sub usage {
    return <<'USAGE';
Usage:
  perl dev/tools/jcpan_bisect_module.pl --module MODULE [options]

Find the first commit where `./jcpan -t MODULE` stopped passing.
The default search scope is the first-parent chain, which is intended for
testing merged PR commits on master/origin/master rather than branch-internal
commits.

Defaults:
  --good is read from dev/cpan-reports/cpan-compatibility-pass.dat
  --bad is master if available, then origin/master, then HEAD
  --timeout is 7200 seconds
  --build-cmd is `make`

Examples:
  perl dev/tools/jcpan_bisect_module.pl --module DBIx::Class \
    --good 9b36f7e57 --bad master --timeout 7200

  perl dev/tools/jcpan_bisect_module.pl --module Moo \
    --good 3fe76ed3b --bad master --timeout 3600 --verify-endpoints

  perl dev/tools/jcpan_bisect_module.pl --module DBIx::Class \
    --bad origin/master --first-parent --isolate-home

Key options:
  --module NAME          CPAN module/distribution to test.
  --good SHA            Known-good commit. Defaults to latest PASS report row.
  --bad SHA             Known-bad commit/branch. Defaults to master when
                        available, then origin/master, then HEAD.
                        For PR-only bisection, pass master/origin/master or
                        another integration branch as the bad endpoint.
  --timeout SEC         Wall-clock timeout for each jcpan run.
  --build-timeout SEC   Wall-clock timeout for each build.
  --build-cmd CMD       Build command before each jcpan run.
  --no-build            Skip build step.
  --jobs N              Pass `--jobs N` to jcpan.
  --worktree DIR        Bisect worktree directory.
  --reuse-worktree      Reuse an existing worktree directory.
  --log-dir DIR         Per-commit logs and cached verdicts.
  --state-dir DIR       Isolated HOME/state directory when --isolate-home is set.
  --first-parent        Use git bisect's first-parent mode for PR-level bisection (default).
  --no-first-parent     Allow git bisect to test commits inside merged PR branches.
  --all-commits         Alias for --no-first-parent.
  --verify-endpoints    Expensively test the good and bad endpoints before bisecting.
  --isolate-home        Run jcpan with HOME and -Duser.home under --state-dir.
  --no-share-sources    Do not symlink existing CPAN sources into isolated HOME.
  --timeout-result bad|skip
  --build-failure bad|skip
  --unknown-result bad|skip

Bisect result mapping:
  good -> git bisect exit 0
  bad  -> git bisect exit 1
  skip -> git bisect exit 125

Every jcpan invocation is bounded by --timeout and killed as a process group.
USAGE
}
