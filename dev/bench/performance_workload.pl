use strict;
use warnings;
use JSON::PP qw(decode_json encode_json);
use Time::HiRes qw(time);

my $workload = shift @ARGV // die "usage: performance_workload.pl WORKLOAD\n";
my $root = $ENV{PERLONJAVA_REPO_ROOT} // '.';
my $json = JSON::PP->new->canonical(1);
my $BATCH_SIZE = {
    method_anchor => 1,
    closure_anchor => 1,
    closure => 512,
    dispatch => 256,
    numeric => 64,
    string => 128,
    regex => 128,
    life => 1,
    json => 32,
}->{$workload} // die "unknown workload: $workload\n";

my ($anchor_reset, $anchor_batch, $anchor_checksum);
if ($workload eq 'method_anchor') {
    local $ENV{PERLONJAVA_BENCH_LOAD_ONLY} = 1;
    my $loaded = do "$root/dev/bench/benchmark_method.pl";
    die "could not load benchmark_method.pl: $@ $!\n" unless $loaded;
    $anchor_reset = \&main::benchmark_method_reset;
    $anchor_batch = \&main::benchmark_method_batch;
    $anchor_reset->();
    $anchor_checksum = $anchor_batch->();
    $anchor_reset->();
}
elsif ($workload eq 'closure_anchor') {
    local $ENV{PERLONJAVA_BENCH_LOAD_ONLY} = 1;
    my $loaded = do "$root/dev/bench/benchmark_closure.pl";
    die "could not load benchmark_closure.pl: $@ $!\n" unless $loaded;
    $anchor_batch = \&main::benchmark_closure_batch;
    $anchor_checksum = $anchor_batch->();
}

my $closure_value = 17;
my $closure = sub { $closure_value * 3 + 2 };

{
    package PerlOnJava::Bench::Dispatch;
    sub read_hash { return $_[0]->{value} + $_[1] }
}
my $dispatch_object = bless { value => 23 }, 'PerlOnJava::Bench::Dispatch';
my $dispatch_method = $dispatch_object->can('read_hash');

our $NUMERIC_GLOBAL = 0;
my $text_seed = 'PerlOnJava-performance-window';
my $regex = qr/([A-Z][a-z]+)-([a-z]+)/;
my $regex_input = 'PerlOnJava-performance-window';
my $json_encoder = JSON::PP->new->canonical(1);
my $json_source = {
    name => 'life-bitpacked',
    dimensions => [128, 100],
    flags => { deterministic => JSON::PP::true, packed => 64 },
    values => [1, 2, 3, 5, 8, 13],
};
my $json_text = $json_encoder->encode($json_source);

if ($workload eq 'life') {
    local $ENV{PERLONJAVA_LIFE_BENCH_LOAD} = 1;
    my $loaded = do "$root/examples/life_bitpacked.pl";
    die "could not load examples/life_bitpacked.pl: $@ $!\n" unless $loaded;
}

my $perform_one;
if ($workload eq 'method_anchor') {
    $perform_one = $anchor_batch;
}
elsif ($workload eq 'closure_anchor') {
    $perform_one = $anchor_batch;
}
elsif ($workload eq 'closure') {
    $perform_one = sub { $closure->() };
}
elsif ($workload eq 'dispatch') {
    $perform_one = sub { $dispatch_method->($dispatch_object, 5) };
}
elsif ($workload eq 'numeric') {
    $perform_one = sub {
        my $local = 0;
        $NUMERIC_GLOBAL = 1;
        for my $i (1 .. 64) {
            $local = (($local * 3) + $i) & 0x7FFFFFFF;
            $NUMERIC_GLOBAL = ($NUMERIC_GLOBAL + ($local & 31)) & 0x7FFFFFFF;
        }
        return $local + $NUMERIC_GLOBAL;
    };
}
elsif ($workload eq 'string') {
    $perform_one = sub {
        my $value = $text_seed;
        $value = substr($value, 2, 18) . ':' . uc(substr($value, 0, 4));
        $value = reverse($value) . substr($text_seed, -5);
        return length($value) . ':' . substr($value, 3, 7);
    };
}
elsif ($workload eq 'regex') {
    $perform_one = sub {
        return $regex_input =~ $regex ? "$1:$2" : 'no-match';
    };
}
elsif ($workload eq 'life') {
    $perform_one = sub {
        return main::benchmark_life_flat64(128, 100, 50);
    };
}
elsif ($workload eq 'json') {
    $perform_one = sub {
        my $decoded = $json_encoder->decode($json_text);
        return $decoded->{values}[5] + $decoded->{dimensions}[0];
    };
}
else {
    die "unhandled workload: $workload\n";
}

$| = 1;
print $json->encode({ ready => JSON::PP::true, workload => $workload }), "\n";

while (my $line = <STDIN>) {
    my $request = eval { decode_json($line) };
    if (!$request || ref($request) ne 'HASH'
            || !defined($request->{seconds}) || $request->{seconds} <= 0) {
        print $json->encode({ error => 'expected a positive seconds field' }), "\n";
        next;
    }

    $anchor_reset->() if $workload eq 'method_anchor';
    my @cpu_before = times();
    my $start = time();
    my $operations = 0;
    my $checksum;
    while (time() - $start < $request->{seconds}) {
        for (1 .. $BATCH_SIZE) {
            $checksum = $perform_one->();
        }
        $operations += $BATCH_SIZE;
    }
    my $wall_seconds = time() - $start;
    my @cpu_after = times();
    my $cpu_seconds = ($cpu_after[0] + $cpu_after[1])
            - ($cpu_before[0] + $cpu_before[1]);
    my $checksum_verified = JSON::PP::false;
    my $reported_checksum = $checksum;
    if ($workload eq 'method_anchor') {
        my $expected = 400_080_000 + ($operations - 1) * 800_000_000;
        die "method anchor checksum mismatch: got $checksum expected $expected\n"
                unless "$checksum" eq "$expected";
        $reported_checksum = $anchor_checksum;
        $checksum_verified = JSON::PP::true;
    }
    elsif ($workload eq 'closure_anchor') {
        die "closure anchor checksum mismatch: got $checksum expected $anchor_checksum\n"
                unless "$checksum" eq "$anchor_checksum";
        $reported_checksum = $anchor_checksum;
        $checksum_verified = JSON::PP::true;
    }
    my $units_per_operation = $workload eq 'life' ? 50
            : ($workload eq 'method_anchor' || $workload eq 'closure_anchor')
                ? 20_000 : 1;
    print $json->encode({
        workload => $workload,
        target_seconds => 0 + $request->{seconds},
        wall_seconds => $wall_seconds,
        cpu_seconds => $cpu_seconds,
        operations => $operations,
        operation_units => $units_per_operation,
        work_units => $operations * $units_per_operation,
        checksum => "$reported_checksum",
        result_checksum => "$checksum",
        checksum_verified => $checksum_verified,
    }), "\n";
}
