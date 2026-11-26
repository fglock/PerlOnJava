use strict;
use warnings;
use JSON::PP qw(decode_json encode_json);
use Time::HiRes qw(clock_gettime CLOCK_MONOTONIC);

my ($kind) = @ARGV;
$| = 1;
die "unknown workload: $kind\n"
    unless defined($kind) && $kind =~ /\A(?:numeric|closure|method|string|regex|eval-string)\z/;

my ($x, $sink, $closure, $object, $eval_id);
$eval_id = 0;
if ($kind eq 'numeric') {
    $x = 0;
} elsif ($kind eq 'closure') {
    my $captured = 17;
    $closure = sub { $captured + 1 };
} elsif ($kind eq 'method') {
    package ThreeEngineBenchObject;
    sub tick {
        $_[0]->{x}++;
        $_[0]->{y}++;
        $_[0]->{x} + $_[0]->{y};
    }
    package main;
    $object = bless { x => 0, y => 0 }, 'ThreeEngineBenchObject';
}

while (my $line = <STDIN>) {
    my $request = decode_json($line);
    my $started = clock_gettime(CLOCK_MONOTONIC);
    my $deadline = $started + $request->{seconds};
    my $operations = 0;
    $sink = 0;
    $x = 0 if $kind eq 'numeric';
    if ($kind eq 'method') {
        $object->{x} = 0;
        $object->{y} = 0;
    }

    if ($kind eq 'numeric') {
        while (clock_gettime(CLOCK_MONOTONIC) < $deadline) {
            for (1 .. 1000) { $x++; $sink += $x; }
            $operations += 1000;
        }
    } elsif ($kind eq 'closure') {
        while (clock_gettime(CLOCK_MONOTONIC) < $deadline) {
            for (1 .. 1000) { $sink += $closure->(); }
            $operations += 1000;
        }
    } elsif ($kind eq 'method') {
        while (clock_gettime(CLOCK_MONOTONIC) < $deadline) {
            for (1 .. 1000) { $sink += $object->tick(); }
            $operations += 1000;
        }
    } elsif ($kind eq 'string') {
        while (clock_gettime(CLOCK_MONOTONIC) < $deadline) {
            for (1 .. 1000) {
                my $value = 'alpha-1234-beta';
                $sink += length('ALPHA' . reverse(substr($value, 6, 4)));
            }
            $operations += 1000;
        }
    } elsif ($kind eq 'eval-string') {
        while (clock_gettime(CLOCK_MONOTONIC) < $deadline) {
            for (1 .. 1000) {
                my $id = ++$eval_id;
                $sink += eval "1 + $id - $id";
            }
            $operations += 1000;
        }
    } else {
        while (clock_gettime(CLOCK_MONOTONIC) < $deadline) {
            for (1 .. 1000) {
                my $value = 'alpha-1234-beta';
                $sink += $1 if $value =~ /alpha-(\d{4})/;
            }
            $operations += 1000;
        }
    }

    print encode_json({
        operations => $operations,
        checksum => "$sink",
        elapsed => clock_gettime(CLOCK_MONOTONIC) - $started,
    }), "\n";
}
