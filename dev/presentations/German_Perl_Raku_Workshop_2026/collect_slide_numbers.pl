#!/usr/bin/env perl
use strict;
use warnings;

use Cwd qw(abs_path);
use File::Basename qw(dirname);
use File::Spec;
use Getopt::Long qw(GetOptions);

my %opt = (
    stats => 1,
    bench => 1,
    iterations => 100_000_000,
    eval_iterations => 2_000,
    eval_payload_len => 50,
    print_cmd => 1,
);

GetOptions(
    'stats!' => \$opt{stats},
    'bench!' => \$opt{bench},
    'iterations=i' => \$opt{iterations},
    'eval-iterations=i' => \$opt{eval_iterations},
    'eval-payload-len=i' => \$opt{eval_payload_len},
    'print-cmd!' => \$opt{print_cmd},
) or die "Invalid options\n";

sub find_repo_root {
    my ($start_dir) = @_;
    my $dir = abs_path($start_dir);

    while (1) {
        my $jperl = File::Spec->catfile($dir, 'jperl');
        my $git = File::Spec->catdir($dir, '.git');
        if (-f $jperl && -d $git) {
            return $dir;
        }
        my $parent = abs_path(File::Spec->catdir($dir, File::Spec->updir()));
        last if !defined($parent) || $parent eq $dir;
        $dir = $parent;
    }

    die "Unable to locate repo root (expected to find ./jperl and .git)\n";
}

sub run_cmd {
    my (%args) = @_;
    my $cmd = $args{cmd};
    my $env = $args{env} || {};

    my $env_prefix = '';
    for my $k (sort keys %$env) {
        my $v = $env->{$k};
        $v =~ s/'/'\\''/g;
        $env_prefix .= "$k='$v' ";
    }

    my $full = $env_prefix . $cmd;
    my $out = `$full 2>&1`;
    my $exit = $? >> 8;
    return ($exit, $out);
}

sub shell_quote {
    my ($s) = @_;
    $s =~ s/'/'\\''/g;
    return "'$s'";
}

sub count_files {
    my (%args) = @_;
    my $root = $args{root};
    my $relative = $args{relative};
    my $pattern = $args{pattern};

    my $dir = File::Spec->catdir($root, $relative);
    return undef if !-d $dir;

    my $cmd = "find '$dir' -type f -name '$pattern' | wc -l";
    my ($exit, $out) = run_cmd(cmd => $cmd);
    return undef if $exit != 0;
    $out =~ s/\s+//g;
    return 0 + $out;
}

sub git_count_commits {
    my ($root) = @_;
    my ($exit, $out) = run_cmd(cmd => "git -C '$root' rev-list --count HEAD");
    return undef if $exit != 0;
    $out =~ s/\s+//g;
    return 0 + $out;
}

sub format_int {
    my ($n) = @_;
    return 'N/A' if !defined $n;
    1 while $n =~ s/^(\d+)(\d{3})/$1,$2/;
    return $n;
}

sub format_vs_baseline {
    my (%args) = @_;
    my $baseline = $args{baseline};
    my $candidate = $args{candidate};

    return 'N/A' if !defined $baseline || !defined $candidate;
    return 'N/A' if $baseline <= 0 || $candidate <= 0;

    my $ratio = $baseline / $candidate;
    if ($ratio >= 1) {
        return sprintf("%.2fx faster", $ratio);
    }
    return sprintf("%.2fx slower", 1 / $ratio);
}

sub format_seconds {
    my ($s) = @_;
    return 'N/A' if !defined $s;
    if ($s < 0.01) {
        return sprintf("%.4fs", $s);
    }
    if ($s < 0.1) {
        return sprintf("%.3fs", $s);
    }
    return sprintf("%.2fs", $s);
}

sub bench_command_seconds {
    my (%args) = @_;
    my $cmd = $args{cmd};
    my $env = $args{env} || {};

    if ($opt{print_cmd}) {
        if (%$env) {
            my @pairs;
            for my $k (sort keys %$env) {
                push @pairs, $k . '=' . $env->{$k};
            }
            print "CMD: " . join(' ', @pairs) . " $cmd\n";
        } else {
            print "CMD: $cmd\n";
        }
    }

    my ($exit, $out) = run_cmd(cmd => $cmd, env => $env);
    die "Benchmark command failed (exit=$exit):\n$cmd\n$out\n" if $exit != 0;

    $out =~ s/^\s+//;
    $out =~ s/\s+$//;

    if ($out !~ /([0-9]+(?:\.[0-9]+)?)/) {
        die "Unexpected benchmark output (expected a number):\n$cmd\n$out\n";
    }

    return 0 + $1;
}

sub print_markdown_table {
    my (%args) = @_;
    my $headers = $args{headers};
    my $rows = $args{rows};

    print "| " . join(" | ", @$headers) . " |\n";
    print "|" . join("|", map { "-" x (length($_) > 3 ? length($_) : 3) } @$headers) . "|\n";
    for my $r (@$rows) {
        print "| " . join(" | ", @$r) . " |\n";
    }
}

my $script_dir = dirname(abs_path($0));
my $repo_root = find_repo_root($script_dir);

if ($opt{stats}) {
    my $java_files = count_files(root => $repo_root, relative => 'src/main/java', pattern => '*.java');
    my $perl_lib_modules = count_files(root => $repo_root, relative => 'src/main/perl/lib', pattern => '*.pm');
    my $commits = git_count_commits($repo_root);

    print "# Slide statistics (generated)\n\n";
    print "- Java source files: " . format_int($java_files) . "\n";
    print "- Perl stdlib modules (src/main/perl/lib/*.pm): " . format_int($perl_lib_modules) . "\n";
    print "- Git commits: " . format_int($commits) . "\n\n";

    print "Notes:\n";
    print "- This script counts files directly from the working tree.\n";
    print "- The slide claims about tests passing / core modules bundled should be verified by whatever harness defines those numbers.\n\n";
}

if ($opt{bench}) {
    my $iters = $opt{iterations};
    my $eval_iters = $opt{eval_iterations};
    my $payload_len = $opt{eval_payload_len};

    my $min_iters = 5_000_000;
    my $min_eval_iters = 500;

    if ($iters < $min_iters) {
        $iters = $min_iters;
    }
    if ($eval_iters < $min_eval_iters) {
        $eval_iters = $min_eval_iters;
    }

    my $jperl = shell_quote(File::Spec->catfile($repo_root, 'jperl'));

    my $perl_loop = sprintf(
        q{perl -MTime::HiRes=time -e 'my $t=time; my $x=0; for (1..%d) { $x++ } print(time-$t, "\n")'},
        $iters
    );
    my $jperl_loop_comp = sprintf(
        q{%s -MTime::HiRes=time -e 'my $t=time; my $x=0; for (1..%d) { $x++ } print(time-$t, "\n")'},
        $jperl,
        $iters
    );
    my $jperl_loop_interp = sprintf(
        q{%s --interpreter -MTime::HiRes=time -e 'my $t=time; my $x=0; for (1..%d) { $x++ } print(time-$t, "\n")'},
        $jperl,
        $iters
    );

    my $perl_eval = sprintf(
        q{perl -MTime::HiRes=time -e 'my $t=time; for my $i (1..%d) { my $code = "($i + 1)"; eval $code; } print(time-$t, "\n")'},
        $eval_iters
    );
    my $jperl_eval_comp = sprintf(
        q{%s -MTime::HiRes=time -e 'my $t=time; for my $i (1..%d) { my $code = "($i + 1)"; eval $code; } print(time-$t, "\n")'},
        $jperl,
        $eval_iters
    );
    my $jperl_eval_interp = sprintf(
        q{%s -MTime::HiRes=time -e 'my $t=time; for my $i (1..%d) { my $payload = "x" x %d; my $code = "$i+1;$payload"; eval $code; } print(time-$t, "\n")'},
        $jperl,
        $eval_iters,
        $payload_len
    );

    my $t_perl = bench_command_seconds(cmd => $perl_loop);
    my $t_comp = bench_command_seconds(cmd => $jperl_loop_comp);
    my $t_interp = bench_command_seconds(cmd => $jperl_loop_interp);

    print "# Loop increment benchmark ($iters iterations)\n\n";

    my $vs_perl_comp = format_vs_baseline(baseline => $t_perl, candidate => $t_comp);
    my $vs_perl_interp = format_vs_baseline(baseline => $t_perl, candidate => $t_interp);

    print_markdown_table(
        headers => ['Implementation', 'Time', 'vs Perl 5'],
        rows => [
            ['Perl 5', format_seconds($t_perl), 'baseline'],
            ['PerlOnJava Compiler', format_seconds($t_comp), $vs_perl_comp],
            ['PerlOnJava Interpreter', format_seconds($t_interp), $vs_perl_interp],
        ],
    );

    print "\n";

    my $t_eval_perl = bench_command_seconds(cmd => $perl_eval);
    my $t_eval_jperl_comp = bench_command_seconds(cmd => $jperl_eval_comp);
    my $t_eval_jperl_interp = bench_command_seconds(cmd => $jperl_eval_interp, env => { JPERL_EVAL_USE_INTERPRETER => 1 });

    print "# eval STRING benchmark ($eval_iters unique evals)\n\n";

    my $vs_perl_eval_interp = format_vs_baseline(baseline => $t_eval_perl, candidate => $t_eval_jperl_interp);
    my $vs_perl_eval_comp = format_vs_baseline(baseline => $t_eval_perl, candidate => $t_eval_jperl_comp);

    print_markdown_table(
        headers => ['Implementation', 'Time', 'vs Perl 5'],
        rows => [
            ['Perl 5', format_seconds($t_eval_perl), 'baseline'],
            ['PerlOnJava (eval via interpreter backend)', format_seconds($t_eval_jperl_interp), $vs_perl_eval_interp],
            ['PerlOnJava (eval via JVM compiler)', format_seconds($t_eval_jperl_comp), $vs_perl_eval_comp],
        ],
    );

    print "\n";

    print "Notes:\n";
    print "- Force full interpreter mode for the whole program via: ./jperl --interpreter ...\n";
    print "- Force eval STRING to use the interpreter backend via: JPERL_EVAL_USE_INTERPRETER=1 ./jperl ...\n";
    print "- You can tune --eval-iterations and --iterations for runtime on slower machines.\n";
}
