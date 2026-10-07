use strict;
use warnings;
use Scalar::Util qw(isweak weaken);
use Test::More;

our @destroyed;

{
    package Issue1649::AggregateCaptureProbe;

    sub DESTROY {
        push @main::destroyed, $_[0]{label};
    }
}

my ($weak, $closure);
sub discard_capture { return }

{
    my @items = (bless { label => 'array' }, 'Issue1649::AggregateCaptureProbe');
    $weak = $items[0];
    weaken($weak);
    $closure = sub { scalar @items };
}

ok(isweak($weak), 'the probe remains a weak reference');
ok(defined $weak, 'the captured aggregate keeps its blessed element alive');
is($closure->(), 1, 'the closure retains the captured aggregate pad');

undef $closure;
ok(!defined $weak, 'releasing the closure releases the aggregate element');
is_deeply(\@destroyed, ['array'], 'the array element is destroyed exactly once');

my ($hash_weak, $hash_closure);
{
    my %items = (item => bless { label => 'hash' }, 'Issue1649::AggregateCaptureProbe');
    $hash_weak = $items{item};
    weaken($hash_weak);
    $hash_closure = sub { scalar keys %items };
}

ok(defined $hash_weak, 'the captured hash keeps its blessed value alive');
is($hash_closure->(), 1, 'the closure retains the captured hash pad');

undef $hash_closure;
ok(!defined $hash_weak, 'releasing the closure releases the hash value');
is_deeply(\@destroyed, ['array', 'hash'], 'both aggregate values are destroyed exactly once');

my $temporary_array_weak;
sub exercise_temporary_array_capture {
    my @items = (bless { label => 'temporary-array' }, 'Issue1649::AggregateCaptureProbe');
    $temporary_array_weak = $items[0];
    weaken($temporary_array_weak);
    discard_capture(sub { scalar @items });
    ok(defined $temporary_array_weak, 'the lexical array owns its element during the scope');
}
exercise_temporary_array_capture();
ok(!defined $temporary_array_weak, 'a temporary array closure releases its capture at frame exit');
is_deeply(\@destroyed, ['array', 'hash', 'temporary-array'],
    'the temporary array capture destroys its element exactly once');

my $temporary_hash_weak;
sub exercise_temporary_hash_capture {
    my %items = (item => bless { label => 'temporary-hash' }, 'Issue1649::AggregateCaptureProbe');
    $temporary_hash_weak = $items{item};
    weaken($temporary_hash_weak);
    discard_capture(sub { scalar keys %items });
    ok(defined $temporary_hash_weak, 'the lexical hash owns its value during the scope');
}
exercise_temporary_hash_capture();
ok(!defined $temporary_hash_weak, 'a temporary hash closure releases its capture at frame exit');
is_deeply(\@destroyed, ['array', 'hash', 'temporary-array', 'temporary-hash'],
    'the temporary hash capture destroys its value exactly once');

done_testing;
