#!/usr/bin/env perl

# Run the performance portfolio in alternating fresh Perl/PerlOnJava pairs.
use strict;
use warnings;
use Cwd qw(abs_path);
use Digest::SHA qw(sha256_hex);
use File::Path qw(make_path);
use File::Spec;
use FindBin qw($Bin);
use Getopt::Long qw(GetOptions);
use IPC::Open3 qw(open3);
use JSON::PP;
use Symbol qw(gensym);

my %option = (pairs => 7, warmup_min => 10, warmup_max => 60, windows => 15,
    window_seconds => 1, timeout => 180, output_dir => 'dev/bench/results');
GetOptions(
    'pairs=i' => \$option{pairs}, 'warmup-min=i' => \$option{warmup_min},
    'warmup-max=i' => \$option{warmup_max}, 'windows=i' => \$option{windows},
    'window-seconds=i' => \$option{window_seconds}, 'timeout=i' => \$option{timeout},
    'output-dir=s' => \$option{output_dir}, 'workload=s@' => \$option{workloads},
    'help' => \$option{help},
) or usage(2);
usage(0) if $option{help};
die "all numeric options must be positive\n" if grep { $option{$_} < 1 } qw(pairs warmup_min warmup_max windows window_seconds timeout);
die "--warmup-max must be at least --warmup-min\n" if $option{warmup_max} < $option{warmup_min};
my @workloads = @{$option{workloads} || [qw(closure method numeric string regex life json)]};
my $root = abs_path(File::Spec->catdir($Bin, '..', '..'));
my $worker = File::Spec->catfile($Bin, 'performance_workload.pl');
my $jperl = File::Spec->catfile($root, 'jperl');
die "missing launcher $jperl; run make before collecting a portfolio\n" unless -x $jperl;
my $stamp = timestamp();
my $output_root = File::Spec->file_name_is_absolute($option{output_dir})
    ? $option{output_dir} : File::Spec->catdir($root, $option{output_dir});
my $directory = File::Spec->catdir($output_root, $stamp);
make_path($directory);
my %result = (schema_version => 1, kind => 'perlonjava-performance-portfolio',
    protocol_compliant => protocol_compliant(\%option), generated_at_utc => $stamp,
    configuration => \%option, workloads => \@workloads, host => host_identity(),
    engines => engine_identity($root, $jperl), results => []);
for my $workload (@workloads) {
    my @pairs;
    for my $pair (1 .. $option{pairs}) {
        my @order = $pair % 2 ? qw(perl perlonjava) : qw(perlonjava perl);
        my %runs;
        for my $engine (@order) {
            $runs{$engine} = invoke($engine, $workload, \%option, $worker, $jperl);
        }
        die "semantic checksum mismatch for $workload pair $pair\n"
            unless $runs{perl}{semantic_checksum} eq $runs{perlonjava}{semantic_checksum};
        push @pairs, { pair => $pair, execution_order => \@order, engines => \%runs };
    }
    push @{$result{results}}, { workload => $workload, pairs => \@pairs };
}
my $output = File::Spec->catfile($directory, 'portfolio.json');
$result{conclusive} = portfolio_conclusive(\%result);
open my $fh, '>:raw', $output or die "cannot write $output: $!\n";
print {$fh} JSON::PP->new->canonical->pretty->encode(\%result);
close $fh or die "cannot close $output: $!\n";
print "$output\n";

sub invoke {
    my ($engine, $workload, $option, $worker, $jperl) = @_;
    my @engine = $engine eq 'perl' ? ('perl') : ('timeout', $option->{timeout}, $jperl);
    my @command = (@engine, $worker, '--workload', $workload, '--window-seconds', $option->{window_seconds}, '--windows', $option->{windows}, '--warmup-min', $option->{warmup_min}, '--warmup-max', $option->{warmup_max});
    open my $fh, '-|', @command or die "cannot start @command: $!\n";
    local $/; my $raw = <$fh>; close $fh;
    die "benchmark failed for $engine/$workload (exit $?)\n" if $? != 0;
    my $decoded = eval { JSON::PP->new->decode($raw) };
    die "invalid benchmark JSON for $engine/$workload: $@\n" unless ref($decoded) eq 'HASH';
    return $decoded;
}

sub engine_identity {
    my ($root, $jperl) = @_;
    my $jar = active_jar($root);
    return {
        perl_version => command_output('perl', '-V'),
        jvm_version => command_output($ENV{PERLONJAVA_JAVA_BIN} || 'java', '-version'),
        jvm_flags => { map { $_ => $ENV{$_} } grep { defined $ENV{$_} }
            qw(JPERL_OPTS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS) },
        jperl_launcher_sha256 => sha256_hex(slurp($jperl)),
        jar => $jar,
        source_commit => chomped(command_output('git', '-C', $root, 'rev-parse', 'HEAD')),
        source_status => command_output('git', '-C', $root, 'status', '--short'),
    };
}
sub host_identity {
    return {
        uname => chomped(command_output('uname', '-a')),
        uptime => chomped(command_output('uptime')),
    };
}
sub active_jar {
    my ($root) = @_;
    my $path = $ENV{PERLONJAVA_JAR};
    if (!defined $path) {
        my @candidate = grep { $_ !~ m{/original-} } glob(File::Spec->catfile($root, 'target', 'perlonjava-*.jar'));
        ($path) = sort { (stat($b))[9] <=> (stat($a))[9] } @candidate;
    }
    return undef unless defined $path && -f $path;
    return { path => abs_path($path), sha256 => sha256_hex(slurp($path)) };
}
sub command_output {
    my @command = @_;
    my $stderr = gensym;
    my $stdout;
    my $pid = eval { open3(undef, $stdout, $stderr, @command) };
    return undef unless $pid;
    my $output = do { local $/; <$stdout> // '' };
    $output .= do { local $/; <$stderr> // '' };
    waitpid($pid, 0);
    return $output;
}
sub chomped { my ($value) = @_; return undef unless defined $value; chomp $value; return $value }
sub slurp { my ($path) = @_; open my $fh, '<:raw', $path or die $!; local $/; return <$fh> }
sub protocol_compliant { my ($o) = @_; return ($o->{pairs} >= 7 && $o->{warmup_min} >= 10 && $o->{warmup_max} >= 60 && $o->{windows} >= 15 && $o->{window_seconds} == 1) ? JSON::PP::true : JSON::PP::false }
sub portfolio_conclusive {
    my ($result) = @_;
    for my $workload (@{$result->{results}}) {
        for my $pair (@{$workload->{pairs}}) {
            for my $engine (qw(perl perlonjava)) {
                return JSON::PP::false unless $pair->{engines}{$engine}{warmup_stabilized};
            }
        }
    }
    return JSON::PP::true;
}
sub timestamp { my @t = gmtime; return sprintf('%04d%02d%02dT%02d%02d%02dZ', $t[5]+1900, $t[4]+1, $t[3], $t[2], $t[1], $t[0]) }
sub usage { my ($status) = @_; print "usage: $0 [--workload NAME] [--pairs N] [--output-dir DIR]\n"; exit $status }
