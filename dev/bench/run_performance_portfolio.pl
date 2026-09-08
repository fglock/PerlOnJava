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
    window_seconds => 1, timeout => 180, output_dir => 'dev/bench/results',
    jfr => 0, jfr_max_size => '32m');
GetOptions(
    'pairs=i' => \$option{pairs}, 'warmup-min=i' => \$option{warmup_min},
    'warmup-max=i' => \$option{warmup_max}, 'windows=i' => \$option{windows},
    'window-seconds=i' => \$option{window_seconds}, 'timeout=i' => \$option{timeout},
    'output-dir=s' => \$option{output_dir}, 'workload=s@' => \$option{workloads},
    'jfr!' => \$option{jfr}, 'jfr-tool=s' => \$option{jfr_tool},
    'jfr-max-size=s' => \$option{jfr_max_size},
    'help' => \$option{help},
) or usage(2);
usage(0) if $option{help};
die "all numeric options must be positive\n" if grep { $option{$_} < 1 } qw(pairs warmup_min warmup_max windows window_seconds timeout);
die "--warmup-max must be at least --warmup-min\n" if $option{warmup_max} < $option{warmup_min};
die "--jfr-max-size must be a positive JFR size such as 32m\n"
    unless $option{jfr_max_size} =~ /^[1-9][0-9]*[kKmMgG]$/;
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
            my $jfr = $option{jfr} && $engine eq 'perlonjava'
                ? File::Spec->catfile($directory, sprintf('%s-pair-%02d.jfr', $workload, $pair))
                : undef;
            $runs{$engine} = invoke($engine, $workload, \%option, $worker, $jperl, $jfr);
            if (defined $jfr) {
                $runs{$engine}{jfr} = artifact($jfr);
                $runs{$engine}{jfr_metrics} = jfr_metrics($jfr, $option{jfr_tool});
            }
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
    my ($engine, $workload, $option, $worker, $jperl, $jfr) = @_;
    my @engine = $engine eq 'perl' ? ('perl') : ('timeout', $option->{timeout}, $jperl);
    my @command = (@engine, $worker, '--workload', $workload, '--window-seconds', $option->{window_seconds}, '--windows', $option->{windows}, '--warmup-min', $option->{warmup_min}, '--warmup-max', $option->{warmup_max});
    local %ENV = %ENV;
    if (defined $jfr) {
        die "JFR output path may not contain whitespace: $jfr\n" if $jfr =~ /\s/;
        $ENV{JPERL_OPTS} = join ' ', grep { length } ($ENV{JPERL_OPTS} // '',
            "-XX:StartFlightRecording=filename=$jfr,dumponexit=true,settings=profile,maxsize=$option->{jfr_max_size}");
    }
    open my $fh, '-|', @command or die "cannot start @command: $!\n";
    local $/; my $raw = <$fh>; close $fh;
    die "benchmark failed for $engine/$workload (exit $?)\n" if $? != 0;
    my ($payload) = grep { /^\{/ } reverse split /\n/, ($raw // '');
    my $decoded = eval { JSON::PP->new->decode($payload // '') };
    die "invalid benchmark JSON for $engine/$workload: $@\n" unless ref($decoded) eq 'HASH';
    return $decoded;
}
sub artifact {
    my ($path) = @_;
    die "expected profiling artifact was not created: $path\n" unless -f $path && -s $path;
    return { path => abs_path($path), sha256 => sha256_hex(slurp($path)), bytes => -s $path };
}
sub jfr_metrics {
    my ($recording, $tool) = @_;
    $tool //= find_jfr_tool();
    die "JFR tool not found; pass --jfr-tool PATH\n" unless defined $tool && -x $tool;
    my $raw = command_output($tool, 'print', '--json', '--events',
        'jdk.GarbageCollection,jdk.ThreadAllocationStatistics', $recording);
    my $document = eval { JSON::PP->new->decode($raw // '') };
    die "cannot parse JFR JSON from $tool: $@\n" unless ref($document) eq 'HASH';
    my (@gc, %latest_thread, $samples);
    for my $event (@{$document->{recording}{events} || []}) {
        my $value = $event->{values} || {};
        if ($event->{type} eq 'jdk.GarbageCollection') { push @gc, duration_seconds($value->{duration}); }
        if ($event->{type} eq 'jdk.ThreadAllocationStatistics') {
            my $id = $value->{thread}{javaThreadId} // 'unknown';
            $latest_thread{$id} = $value->{allocated} if !exists($latest_thread{$id}) || $value->{allocated} > $latest_thread{$id};
        }
    }
    my $summary = command_output($tool, 'summary', $recording) // '';
    ($samples) = $summary =~ /^\s*jdk\.ObjectAllocationSample\s+(\d+)\s+/m;
    my $gc_seconds = 0; $gc_seconds += $_ for @gc;
    my $allocated = 0; $allocated += $_ for values %latest_thread;
    return { gc_count => 0 + @gc, gc_pause_seconds => 0 + $gc_seconds,
        gc_longest_pause_seconds => @gc ? 0 + (sort { $b <=> $a } @gc)[0] : 0,
        thread_allocated_bytes => 0 + $allocated, allocation_sample_count => 0 + ($samples // 0) };
}
sub duration_seconds { my ($duration) = @_; return 0 unless defined $duration && $duration =~ /^PT([0-9.]+)S$/; return 0 + $1 }
sub find_jfr_tool {
    return "$ENV{JAVA_HOME}/bin/jfr" if defined($ENV{JAVA_HOME}) && -x "$ENV{JAVA_HOME}/bin/jfr";
    if (-x '/usr/libexec/java_home') { my $home = chomped(command_output('/usr/libexec/java_home')); return "$home/bin/jfr" if defined($home) && -x "$home/bin/jfr"; }
    return undef;
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
sub usage { my ($status) = @_; print "usage: $0 [--workload NAME] [--pairs N] [--output-dir DIR] [--jfr-max-size 32m]\n"; exit $status }
