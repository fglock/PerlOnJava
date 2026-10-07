use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

our @destroyed;
our $captured_destroyed = 0;

{
    package Issue1649::DyingDestroy;

    sub DESTROY {
        push @main::destroyed, $_[0]{id};
        die "broken destructor\n";
    }
}

{
    package Issue1649::CapturedDyingDestroy;

    sub DESTROY {
        ++$main::captured_destroyed;
        die "captured destructor failed\n";
    }
}

my ($weak_first, $warnings, $observed_at);
my $expected_at = 'caller exception';
{
    local $SIG{__WARN__} = sub { $warnings .= $_[0] };
    my $first = bless { id => 'first' }, 'Issue1649::DyingDestroy';
    $weak_first = $first;
    weaken($weak_first);
    my $second = bless { id => 'second' }, 'Issue1649::DyingDestroy';

    $@ = $expected_at;
    undef $second;
    undef $first;
    $observed_at = $@;
}

is_deeply(\@destroyed, [qw(second first)],
    'later destructors still run in order when DESTROY dies');
ok(!defined($weak_first),
    'weak references clear after a DESTROY exception');
is($warnings,
    "\t(in cleanup) broken destructor\n" x 2,
    'each suppressed DESTROY exception emits the cleanup warning');
is($observed_at, $expected_at,
    '$@ is preserved across DESTROY exceptions');

my ($captured_weak, $captured_warnings, $captured_at);
my $expected_captured_at = 'captured caller exception';
{
    local $SIG{__WARN__} = sub { $captured_warnings .= $_[0] };
    my $object = bless {}, 'Issue1649::CapturedDyingDestroy';
    $captured_weak = $object;
    weaken($captured_weak);
    my $reader = sub { $object };
    ok(defined($captured_weak), 'closure capture owns its referent before release');
    is(ref($reader->()), 'Issue1649::CapturedDyingDestroy',
        'closure reads the captured referent before scope exit');
    $@ = $expected_captured_at;
}
$captured_at = $@;
is($captured_at, $expected_captured_at,
    '$@ is preserved when closure release invokes a dying DESTROY');
is($captured_destroyed, 1,
    'releasing the last closure capture invokes DESTROY once');
ok(!defined($captured_weak),
    'closure release clears the weak reference after DESTROY throws');
is($captured_warnings, "\t(in cleanup) captured destructor failed\n",
    'closure release reports the suppressed DESTROY exception');

done_testing();
