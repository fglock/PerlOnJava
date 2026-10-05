use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

our @destroyed;

{
    package Issue1649::DyingDestroy;

    sub DESTROY {
        push @main::destroyed, $_[0]{id};
        die "broken destructor\n";
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

done_testing();
