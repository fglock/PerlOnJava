use strict;
use warnings;
use Test::More;

# This is the semantic contract that a Phase 3 RuntimeCode.apply consolidation
# must retain for the high-frequency normal (named-argument) call path.
sub mutate_and_identify_caller {
    $_[0] = 'callee-mutated';
    return (caller(1))[3];
}

sub named_call_boundary {
    my ($value) = @_;
    return mutate_and_identify_caller($value);
}

is(named_call_boundary('caller-value'), 'main::named_call_boundary',
    'normal sub call preserves the immediate caller frame');
my $value = 'caller-value';
mutate_and_identify_caller($value);
is($value, 'callee-mutated', 'normal sub arguments remain aliases to caller variables');

sub hasargs { return (caller(0))[4] ? 1 : 0 }
sub normal_hasargs { return hasargs() }
sub shared_hasargs {
    @_ = ('shared');
    return &hasargs;
}

is(normal_hasargs(), 1, 'normal call records caller hasargs');
is(shared_hasargs(), 0, 'shared-argument call remains distinguishable to caller');

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    sub callee_suppresses_uninitialized {
        no warnings 'uninitialized';
        my $missing;
        return $missing . 'callee';
    }
    is(callee_suppresses_uninitialized(), 'callee',
        'callee lexical warning scope applies during the call');
    my $missing;
    my $result = $missing . 'caller';
    is($result, 'caller', 'caller continues after callee warning scope exits');
}
is(scalar @warnings, 1, 'caller warning scope is restored after the callee returns');

sub return_from_map { return map { $_ * 2 } @_ }
is_deeply([return_from_map(2, 3)], [4, 6],
    'nonlocal return through a nested map block preserves list context');

done_testing;
