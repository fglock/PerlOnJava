use strict;
use warnings;
use Test::More;
use Scalar::Util qw(weaken);

our ($weak_source, @events);

{
    package Issue2043::Source;
    sub DESTROY { push @main::events, 'source-destroyed' }

    package Issue2043::Probe;
    sub DESTROY {
        push @main::events,
            defined($main::weak_source) ? 'probe-saw-source-live' : 'probe-saw-source-cleared';
    }
}

sub run_case {
    my ($root_kind) = @_;
    @events = ();

    my $source = bless {}, 'Issue2043::Source';
    $weak_source = $source;
    weaken($weak_source);

    my $nested = { source => $source };
    my $probe = bless {}, 'Issue2043::Probe';
    my $ordered_children = [ $probe, $nested ];
    my $root = $root_kind eq 'hash'
        ? { children => $ordered_children }
        : $ordered_children;

    undef $source;
    undef $nested;
    undef $probe;
    undef $ordered_children;
    undef $root;
    return [@events];
}

my $array_events = run_case('array');
is_deeply(
    $array_events,
    ['source-destroyed', 'probe-saw-source-cleared'],
    'array teardown drains a nested child before its earlier sibling');

my $hash_events = run_case('hash');
is_deeply(
    $hash_events,
    ['source-destroyed', 'probe-saw-source-cleared'],
    'hash teardown drains a nested child before its earlier sibling');

done_testing();
