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
);

GetOptions(
    'stats!' => \$opt{stats},
    'bench!' => \$opt{bench},
    'iterations=i' => \$opt{iterations},
    'eval-iterations=i' => \$opt{eval_iterations},
    'eval-payload-len=i' => \$opt{eval_payload_len},
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

sub bench_command_seconds {
    my (%args) = @_;
    my $cmd = $args{cmd};
    my $env = $args{env} || {};

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

    my $perl_loop = "perl -MTime::HiRes=time -e 'my \\$t=time; my \\$x=0; for (1..$iters) { \\$x++ } printf ""%.2f\\n"", time-\\$t'";
    my $jperl_loop_comp = "'$repo_root/jperl' -MTime::HiRes=time -e 'my \\$t=time; my \\$x=0; for (1..$iters) { \\$x++ } printf ""%.2f\\n"", time-\\$t'";
    my $jperl_loop_interp = "'$repo_root/jperl' --interpreter -MTime::HiRes=time -e 'my \\$t=time; my \\$x=0; for (1..$iters) { \\$x++ } printf ""%.2f\\n"", time-\\$t'";

    my $perl_eval = "perl -MTime::HiRes=time -e 'my \\$t=time; for my \\$i (1..$eval_iters) { my \\$code = \\\"(\\$i + 1)\\\"; eval \\$code; } printf ""%.2f\\n"", time-\\$t'";
    my $jperl_eval_comp = "'$repo_root/jperl' -MTime::HiRes=time -e 'my \\$t=time; for my \\$i (1..$eval_iters) { my \\$code = \\\"(\\$i + 1)\\\"; eval \\$code; } printf ""%.2f\\n"", time-\\$t'";
    my $jperl_eval_interp = "'$repo_root/jperl' -MTime::HiRes=time -e 'my \\$t=time; for my \\$i (1..$eval_iters) { my \\$payload = q{x} x $payload_len; my \\$code = \\\"\\$i+1;\\$payload\\\"; eval \\$code; } printf ""%.2f\\n"", time-\\$t'";

    my $t_perl = bench_command_seconds(cmd => $perl_loop);
    my $t_comp = bench_command_seconds(cmd => $jperl_loop_comp);
    my $t_interp = bench_command_seconds(cmd => $jperl_loop_interp);

    print "# Loop increment benchmark ($iters iterations)\n\n";

    my $vs_perl_comp = $t_comp > 0 ? sprintf("%.2fx faster", $t_perl / $t_comp) : 'N/A';
    my $vs_perl_interp = $t_interp > 0 ? sprintf("%.2fx", $t_perl / $t_interp) : 'N/A';

    print_markdown_table(
        headers => ['Implementation', 'Time', 'vs Perl 5'],
        rows => [
            ['Perl 5', sprintf("%.2fs", $t_perl), 'baseline'],
            ['PerlOnJava Compiler', sprintf("%.2fs", $t_comp), $vs_perl_comp],
            ['PerlOnJava Interpreter', sprintf("%.2fs", $t_interp), $vs_perl_interp],
        ],
    );

    print "\n";

    my $t_eval_perl = bench_command_seconds(cmd => $perl_eval);
    my $t_eval_jperl_comp = bench_command_seconds(cmd => $jperl_eval_comp);
    my $t_eval_jperl_interp = bench_command_seconds(cmd => $jperl_eval_interp, env => { JPERL_EVAL_USE_INTERPRETER => 1 });

    print "# eval STRING benchmark ($eval_iters unique evals)\n\n";

    my $vs_perl_eval_interp = $t_eval_perl > 0 ? sprintf("%.0f%% %s", abs(100 * ($t_eval_jperl_interp - $t_eval_perl) / $t_eval_perl), ($t_eval_jperl_interp <= $t_eval_perl ? 'faster' : 'slower')) : 'N/A';
    my $vs_perl_eval_comp = $t_eval_perl > 0 ? sprintf("%.1fx %s", ($t_eval_jperl_comp / $t_eval_perl), ($t_eval_jperl_comp >= $t_eval_perl ? 'slower' : 'faster')) : 'N/A';

    print_markdown_table(
        headers => ['Implementation', 'Time', 'vs Perl 5'],
        rows => [
            ['Perl 5', sprintf("%.2fs", $t_eval_perl), 'baseline'],
            ['PerlOnJava (eval via interpreter backend)', sprintf("%.2fs", $t_eval_jperl_interp), $vs_perl_eval_interp],
            ['PerlOnJava (eval via JVM compiler)', sprintf("%.2fs", $t_eval_jperl_comp), $vs_perl_eval_comp],
        ],
    );

    print "\n";

    print "Notes:\n";
    print "- Force full interpreter mode for the whole program via: ./jperl --interpreter ...\n";
    print "- Force eval STRING to use the interpreter backend via: JPERL_EVAL_USE_INTERPRETER=1 ./jperl ...\n";
    print "- You can tune --eval-iterations and --iterations for runtime on slower machines.\n";
}
