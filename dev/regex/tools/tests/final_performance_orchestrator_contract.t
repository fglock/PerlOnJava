use strict;
use warnings;

use Cwd qw(abs_path);
use File::Path qw(make_path);
use File::Spec;
use File::Temp qw(tempdir);
use FindBin;
use IPC::Cmd qw(can_run);
use Test::More;

my $tools = File::Spec->rel2abs(File::Spec->catdir($FindBin::Bin, '..'));
my $producer = File::Spec->catfile($tools,
    'run_final_performance.pl');
open my $source_fh, '<:raw', $producer or die "Cannot read $producer: $!";
my $source = do { local $/; <$source_fh> };
close $source_fh;

like($source, qr/my \@order = qw\(baseline candidate candidate baseline\)/,
    'producer owns the exact alternating ordered-run sequence');
like($source,
    qr/for my \$backend \(qw\(jvm interpreter\)\).*?pat_psycho\.t.*?pat_psycho_thr\.t.*?speed\.t.*?speed_thr\.t/s,
    'producer owns both backends and the exact four psycho/speed tests');
like($source, qr/--samples', '5'/,
    'producer owns the exact five-pair ordinary sample count');
unlike($source, qr/GetOptions\([^;]*['"]input[=:]/s,
    'producer has no option to consume preexisting evidence');

my $temporary = tempdir(CLEANUP => 1);
my $baseline = File::Spec->catdir($temporary, 'baseline');
my $candidate = File::Spec->catdir($temporary, 'candidate');
my $perl5 = File::Spec->catdir($temporary, 'perl5');
init_repo($baseline, 1, 'baseline');
init_repo($candidate, 2, 'candidate');
init_repo($perl5, 1, 'perl5');

my $marker = File::Spec->catfile($temporary, 'launcher-ran');
my $java_binding_marker = File::Spec->catfile($temporary, 'java-binding');
my $fake = File::Spec->catfile($temporary, 'fake-executable');
open my $fake_fh, '>:raw', $fake or die $!;
print {$fake_fh} "#!/bin/sh\nprintf '%s' \"\$0\" > '$marker'\nprintf '%s' \"\$PERLONJAVA_JAVA_BIN\" > '$java_binding_marker'\nprintf attacker-selected > '$fake'\nexit 99\n";
close $fake_fh;
chmod 0700, $fake;
my $plain = File::Spec->catfile($temporary, 'plain-input');
open my $plain_fh, '>:raw', $plain or die $!;
print {$plain_fh} "fixture\n";
close $plain_fh;
chmod 0600, $plain or die $!;
my $fixture = File::Spec->catdir($temporary, 'fixture');
make_path(File::Spec->catdir($fixture, 't'));
open my $test_fh, '>:raw', File::Spec->catfile($fixture, 't', '87ordered.t')
    or die $!;
print {$test_fh} "1;\n";
close $test_fh;
my $output = File::Spec->catdir($temporary, 'evidence');
make_path($output, { mode => 0700 });
chmod 0700, $output;
my $git = can_run('git') or die 'git is required';
my $ps = can_run('ps') or die 'ps is required';
my $uptime = can_run('uptime') or die 'uptime is required';

my @command = ($^X, $producer,
    '--baseline-source', $baseline,
    '--candidate-source', $candidate,
    '--perl5-source', $perl5,
    '--baseline-jar', $plain,
    '--candidate-jar', $plain,
    '--baseline-launcher', $fake,
    '--candidate-launcher', $fake,
    '--interpreter-launcher', $fake,
    '--java', $fake,
    '--perl', $^X,
    '--jfr-tool', $fake,
    '--jfc', $plain,
    '--time', $fake,
    '--git', $git,
    '--ps', $ps,
    '--uptime', $uptime,
    '--ordered-fixture-template', $fixture,
    '--ordered-fixture-manifest', $plain,
    '--dbix-archive', $plain,
    '--authority-key', $plain,
    '--output-root', $output,
);
delete local $ENV{GIT_PAGER};
my ($status, $diagnostic) = capture(@command);
isnt($status, 0, 'authority-selected repositories with a false parent relation reject');
like($diagnostic, qr/direct child of baseline/,
    'rejection is based on actual Git objects and relation');
ok(!-e $marker,
    'no authority-selected executable runs before source provenance passes');

{
    local $ENV{JAVA_TOOL_OPTIONS} = '-agentlib:attacker';
    my ($ambient_status, $ambient_diagnostic) = capture(@command);
    isnt($ambient_status, 0, 'ambient JVM injection is rejected');
    like($ambient_diagnostic, qr/ambient execution-injection variables.*JAVA_TOOL_OPTIONS/,
        'ambient rejection identifies the unsealed variable');
    ok(!-e $marker, 'ambient rejection occurs before any selected executable runs');
}

my $related_baseline = File::Spec->catdir($temporary, 'related-baseline');
my $parent = git_output($candidate, qw(rev-parse HEAD^));
git($candidate, 'worktree', 'add', '-q', '--detach', $related_baseline, $parent);
my $sealed_output = File::Spec->catdir($temporary, 'sealed-execution');
make_path($sealed_output, { mode => 0700 });
chmod 0700, $sealed_output;
my @sealed_command = @command;
for my $index (0 .. $#sealed_command - 1) {
    $sealed_command[$index + 1] = $related_baseline
        if $sealed_command[$index] eq '--baseline-source';
    $sealed_command[$index + 1] = $sealed_output
        if $sealed_command[$index] eq '--output-root';
}
($status, $diagnostic) = capture(@sealed_command);
isnt($status, 0, 'controlled layout-sensitive Java mutation fails closed');
open my $marker_fh, '<:raw', $marker or die "sealed Java did not run: $diagnostic";
my $executed_java = do { local $/; <$marker_fh> };
close $marker_fh;
is($executed_java, abs_path($fake),
    'execution preserves the authority-selected installation path');
open my $binding_fh, '<:raw', $java_binding_marker or die $!;
my $bound_java = do { local $/; <$binding_fh> };
close $binding_fh;
is($bound_java, abs_path($fake),
    'closed child environment binds launchers to authority-selected Java');
like($diagnostic, qr/authority-selected executable identity changed/,
    'immediate post-execution hashing detects the mutation window');
open my $mutated_fh, '<:raw', $fake or die $!;
my $mutated = do { local $/; <$mutated_fh> };
close $mutated_fh;
is($mutated, 'attacker-selected',
    'synthetic original-tool swap occurred during execution');

($status, $diagnostic) = capture($^X, $producer, '--input',
    File::Spec->catfile($temporary, 'forged.json'));
isnt($status, 0, 'a forged preexisting evidence input is rejected');
like($diagnostic, qr/(?:Unknown option: input|Usage:)/,
    'producer rejects rather than replays an evidence document');

done_testing;

sub init_repo {
    my ($path, $commits, $label) = @_;
    make_path($path);
    git($path, qw(init -q));
    for my $index (1 .. $commits) {
        my $file = File::Spec->catfile($path, "commit-$index");
        open my $fh, '>:raw', $file or die $!;
        print {$fh} "$label $index\n";
        close $fh;
        git($path, 'add', ".");
        git($path, '-c', 'user.name=RegexImplementation Test', '-c',
            'user.email=regex_implementation-test@example.invalid', 'commit', '-q',
            '-m', "fixture $index");
    }
}

sub git {
    my ($path, @args) = @_;
    system('git', '-C', $path, @args) == 0
        or die "git @args failed in $path\n";
}

sub git_output {
    my ($path, @args) = @_;
    open my $fh, '-|', 'git', '-C', $path, @args or die $!;
    my $output = do { local $/; <$fh> };
    close $fh or die "git @args failed in $path\n";
    $output =~ s/\s+\z//;
    return $output;
}

sub capture {
    my (@argv) = @_;
    my $pid = open my $pipe, '-|';
    die "Cannot fork: $!" unless defined $pid;
    if ($pid == 0) {
        open STDERR, '>&', STDOUT or die $!;
        exec { $argv[0] } @argv or die "Cannot exec $argv[0]: $!";
    }
    my $output = do { local $/; <$pipe> };
    close $pipe;
    return ($? >> 8, $output);
}
