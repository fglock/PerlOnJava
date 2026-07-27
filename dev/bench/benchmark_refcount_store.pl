use strict;
use warnings;
use Benchmark;
use Scalar::Util qw(weaken);

{
    package RefcountStoreBench;

    sub DESTROY {
        $main::destroyed++;
    }
}

our $destroyed = 0;
my $object = bless { value => 1 }, 'RefcountStoreBench';
my $weak = $object;
weaken($weak);

# Keep enough container slots active to exercise tracked overwrite ownership
# without measuring allocation of a fresh aggregate on every iteration.
my @slots = map { $object } 0 .. 1023;
my $sink = 0;

sub loop_refcount_stores {
    for my $i (0 .. 999_999) {
        $slots[$i & 1023] = $object;
        $sink += $slots[$i & 1023]{value};
    }
}

timethis(30, sub {
    $sink = 0;
    loop_refcount_stores();
});

die "tracked object destroyed during benchmark\n"
    if $destroyed || !defined $weak;
print "done $sink\n";
