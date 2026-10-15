use strict;
use warnings;
use JSON::PP qw(decode_json encode_json);
use Time::HiRes qw(time);

my $workload = shift @ARGV // die "usage: performance_workload.pl WORKLOAD\n";
my $root = $ENV{PERLONJAVA_REPO_ROOT} // '.';
my $json = JSON::PP->new->canonical(1);
my $BATCH_SIZE = {
    closure => 512,
    dispatch => 256,
    numeric => 64,
    string => 128,
    regex => 128,
    life => 1,
    json => 32,
}->{$workload} // die "unknown workload: $workload\n";

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

my $perform_one = sub {
    return $closure->() if $workload eq 'closure';
    return $dispatch_method->($dispatch_object, 5) if $workload eq 'dispatch';

    if ($workload eq 'numeric') {
        my $local = 0;
        $NUMERIC_GLOBAL = 1;
        for my $i (1 .. 64) {
            $local = (($local * 3) + $i) & 0x7FFFFFFF;
            $NUMERIC_GLOBAL = ($NUMERIC_GLOBAL + ($local & 31)) & 0x7FFFFFFF;
        }
        return $local + $NUMERIC_GLOBAL;
    }

    if ($workload eq 'string') {
        my $value = $text_seed;
        $value = substr($value, 2, 18) . ':' . uc(substr($value, 0, 4));
        $value = reverse($value) . substr($text_seed, -5);
        return length($value) . ':' . substr($value, 3, 7);
    }

    if ($workload eq 'regex') {
        return $regex_input =~ $regex ? "$1:$2" : 'no-match';
    }

    if ($workload eq 'life') {
        return main::benchmark_life_flat64(128, 100, 50);
    }

    if ($workload eq 'json') {
        my $decoded = $json_encoder->decode($json_text);
        return $decoded->{values}[5] + $decoded->{dimensions}[0];
    }

    die "unhandled workload: $workload\n";
};

$| = 1;
print $json->encode({ ready => JSON::PP::true, workload => $workload }), "\n";

while (my $line = <STDIN>) {
    my $request = eval { decode_json($line) };
    if (!$request || ref($request) ne 'HASH'
            || !defined($request->{seconds}) || $request->{seconds} <= 0) {
        print $json->encode({ error => 'expected a positive seconds field' }), "\n";
        next;
    }

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
    my $units_per_operation = $workload eq 'life' ? 50 : 1;
    print $json->encode({
        workload => $workload,
        target_seconds => 0 + $request->{seconds},
        wall_seconds => $wall_seconds,
        cpu_seconds => $cpu_seconds,
        operations => $operations,
        operation_units => $units_per_operation,
        work_units => $operations * $units_per_operation,
        checksum => "$checksum",
    }), "\n";
}
