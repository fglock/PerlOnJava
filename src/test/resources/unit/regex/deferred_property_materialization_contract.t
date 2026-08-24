use strict;
use warnings;
use Test::More tests => 35;
use re 'eval';

our (%calls, @order, $forward, $nested);

sub IsA33Defined {
    ++$calls{defined};
    return "0041\n";
}

my $defined_source = '\\p{IsA33Defined}';
is($calls{defined} || 0, 0, 'defined callback has not run before construction');
my $defined = qr/^$defined_source$/;
is($calls{defined}, 1, 'defined callback runs during construction');
ok('A' =~ $defined, 'materialized property matches');
ok('A' =~ $defined, 'materialized property remains reusable');
is($calls{defined}, 1, 'successful materialization is cached');

BEGIN { $forward = qr/^(?:Z|\p{IsA33Forward})$/ }
sub IsA33Forward {
    ++$calls{forward};
    return "0042\n";
}

ok('Z' =~ $forward, 'unreached forward property does not block construction');
is($calls{forward} || 0, 0, 'optimizer-skipped forward property stays lazy');
ok('B' =~ $forward, 'forward property resolves when its opcode is reached');
is($calls{forward}, 1, 'forward property callback runs once');
ok('B' =~ $forward, 'forward property result remains reusable');
is($calls{forward}, 1, 'forward success remains cached');

sub IsA33First {
    push @order, 'first';
    return "0041\n";
}
sub IsA33Second {
    push @order, 'second';
    return "0042\n";
}
my $ordered_source = '\\p{IsA33First}\\p{IsA33First}\\p{IsA33Second}';
my $ordered = qr/^$ordered_source$/;
is_deeply(\@order, [qw(first second)],
    'defined callbacks materialize once per key in source order');
ok('AAB' =~ $ordered, 'duplicate materialized terms retain match semantics');

sub IsA33Fail {
    ++$calls{failure};
    die "a33-sticky-$calls{failure}\n";
}
my $failure_source = '\\p{IsA33Fail}';
my @failures;
for (1 .. 2) {
    my $compiled = eval { qr/$failure_source/ };
    push @failures, $@;
    ok(!defined($compiled), "failed construction $_ returns no regex");
}
is($calls{failure}, 1, 'defined callback failure is sticky');
like($failures[0], qr/a33-sticky-1/, 'first construction reports callback failure');
is($failures[1], $failures[0], 'later construction reports the cached failure');

{
    package A33One;
    sub IsLocal { ++$main::calls{one}; return "0044\n" }
    $main::calls{one_regex} = qr/^\p{IsLocal}$/;
}
{
    package A33Two;
    sub IsLocal { ++$main::calls{two}; return "0045\n" }
    $main::calls{two_regex} = qr/^\p{IsLocal}$/;
}
ok('D' =~ $calls{one_regex} && 'E' !~ $calls{one_regex},
    'first construction package is retained');
ok('E' =~ $calls{two_regex} && 'D' !~ $calls{two_regex},
    'same source in another package has independent identity');
is_deeply([@calls{qw(one two)}], [1, 1], 'package-local callbacks each run once');

sub IsA33Fold {
    ++$calls{$_[0] ? 'folded' : 'sensitive'};
    return $_[0] ? "0066\n" : "0046\n";
}
my $fold_source = '\\p{IsA33Fold}';
my $sensitive = qr/^$fold_source$/;
my $folded = qr/^$fold_source$/i;
ok('F' =~ $sensitive && 'f' !~ $sensitive, 'sensitive expansion stays sensitive');
ok('f' =~ $folded, 'folded expansion uses its distinct callback argument');
is_deeply([@calls{qw(sensitive folded)}], [1, 1],
    'sensitive and folded cache keys are independent');

{
    my $cache_source = '\\p{IsA33CacheHit}';
    my $before_definition = eval { qr/^$cache_source$/ };
    is($@, '', 'undefined cache-hit source constructs before definition');
    ok(defined($before_definition), 'first construction retains a deferred program');
    is($calls{cache_hit} || 0, 0, 'first construction does not invoke an undefined callback');
    {
        no strict 'refs';
        *{'main::IsA33CacheHit'} = sub {
            ++$calls{cache_hit};
            return "0048\n";
        };
    }
    my $after_definition = eval { qr/^$cache_source$/ };
    is($calls{cache_hit}, 1,
        'identical cache-hit construction materializes before matching');
    ok('H' =~ $after_definition, 'cache-hit materialized program matches');
}

$nested = qr/^(??{ '\\p{IsNestedLocal}' })$/;
{
    package A33NestedMatch;
    sub IsNestedLocal {
        ++$main::calls{nested};
        return "0047\n";
    }
    main::ok('G' =~ $main::nested,
        'raw nested source uses the active match-site package');
    main::ok('G' =~ $main::nested, 'nested materialization remains reusable');
}
is($calls{nested}, 1, 'nested defined callback runs once');

my $missing_source = '\\p{IsA33StillMissing}';
my $missing = eval { qr/$missing_source/ };
is($@, '', 'undefined forward property constructs successfully');
my $missing_result = eval { 'A' =~ $missing ? 1 : 0 };
ok(!defined($missing_result), 'undefined forward property fails when reached');
like($@, qr/IsA33StillMissing/, 'reached undefined property names the missing callback');
