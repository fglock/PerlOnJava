use strict;
use warnings;
use Test::More;
use re 'eval';

no warnings 'once';
no warnings 'experimental::regex_sets';

our ($lazy, $optional, $optimized, $union, $outer_negation, $folded, $late);
our ($lazy_calls, $optional_calls, $optimized_calls, $union_calls, @fold_modes);

BEGIN {
    $lazy = qr/^(?:Z|\p{IsA24Lazy})$/;
    $optional = qr/^(?:\p{IsA24Optional})?$/;
    $optimized = qr/^\p{IsA24Optimized}Z$/;
    $union = qr/^[B\p{IsA24Union}]$/;
    $outer_negation = qr/^[^\P{IsA24Union}]$/;
    $folded = qr/^\p{IsA24Folded}$/i;
    $late = qr/^(?:Z|\p{IsA24InstalledLater})$/;
}

sub IsA24Lazy      { ++$lazy_calls;      "0041\n" }
sub IsA24Optional  { ++$optional_calls;  "0041\n" }
sub IsA24Optimized { ++$optimized_calls; "0041\n" }
sub IsA24Union     { ++$union_calls;     "0041\n" }
sub IsA24Folded {
    push @fold_modes, $_[0] ? 'i' : 's';
    return $_[0] ? "0061\n" : "0041\n";
}

ok('Z' =~ $lazy, 'earlier alternative matches');
is($lazy_calls || 0, 0, 'unreached property callback stays lazy');
ok('A' =~ $lazy, 'reached property matches');
is($lazy_calls, 1, 'reached property resolves once');

ok('' =~ $optional, 'optional property can be skipped');
is($optional_calls || 0, 0, 'skipped optional property is not resolved');
ok('QQQ' !~ $optimized, 'following literal rejects all candidates');
is($optimized_calls || 0, 0, 'following-literal optimization does not resolve property');
ok('AZ' =~ $optimized, 'candidate reaches optimized property');
is($optimized_calls, 1, 'optimized property resolves when reached');

ok('A' =~ $union, 'deferred property member survives static union');
ok('B' =~ $union, 'static member survives deferred union');
ok('C' !~ $union, 'union excludes unrelated member');
ok('A' =~ $outer_negation, 'outer negation and token negation remain separate');
ok('B' !~ $outer_negation, 'double-negated class excludes non-property member');
is($union_calls, 1, 'same property result is cached across class shapes');

ok('a' =~ $folded, '/i callback result includes returned lowercase member');
ok('A' !~ $folded, '/i callback result is authoritative, not case-closed again');
is_deeply(\@fold_modes, ['i'], '/i mode is passed once and cached separately');

my $unreached_late = eval { 'Z' =~ $late ? 1 : 0 };
is($@, '', 'unknown property is harmless while its branch is unreached');
is($unreached_late, 1, 'unknown-property regex still takes earlier branch');
my $first_late = eval { 'A' =~ $late ? 1 : 0 };
like($@, qr/IsA24InstalledLater/, 'reaching unknown property reports its name');
ok(!defined($first_late), 'unknown property reach aborts the match');
{
    no strict 'refs';
    *{'main::IsA24InstalledLater'} = sub { "0041\n" };
}
my $retried_late = eval { 'A' =~ $late ? 1 : 0 };
is($@, '', 'failed unknown resolution can be retried after installation');
is($retried_late, 1, 'same compiled regex uses later property definition');

our $runtime_defined_calls;
sub IsA24RuntimeDefined { ++$runtime_defined_calls; "0044\n" }
my $defined_source = '\\p{IsA24RuntimeDefined}';
my $runtime_defined = eval { qr/^(?:Z|$defined_source)$/ };
is($@, '', 'defined runtime-constructed property compiles');
is($runtime_defined_calls, 1, 'defined runtime property resolves at construction');
ok('D' =~ $runtime_defined, 'defined runtime property matches');
is($runtime_defined_calls, 1, 'defined runtime property remains cached');

my $missing_source = '\\p{IsA24RuntimeMissing}';
my $runtime_missing = eval { qr/^(?:Z|$missing_source)$/ };
is($@, '', 'unknown runtime-constructed ordinary property is deferred');
ok('Z' =~ $runtime_missing, 'runtime unknown can remain unreachable');
my $runtime_missing_match = eval { 'A' =~ $runtime_missing ? 1 : 0 };
like($@, qr/main::IsA24RuntimeMissing/, 'runtime unknown errors only when reached, qualified');
ok(!defined($runtime_missing_match), 'runtime unknown reach aborts matching');

my $extended = eval q{ qr/^(?[\p{IsA24ExtendedMissing}])$/ };
like($@, qr/IsA24ExtendedMissing/, 'forward unknown in extended class is a construction error');
ok(!defined($extended), 'extended unknown does not produce a regex');

my $dynamic_missing = qr/^(?:Z|(??{ '\\p{IsA24DynamicMissing}' }))$/;
my $dynamic_unreached = eval { 'Z' =~ $dynamic_missing ? 1 : 0 };
is($@, '', 'unknown dynamic property is harmless on an earlier alternative');
is($dynamic_unreached, 1, 'dynamic regex still takes its earlier alternative');
my $dynamic_reached = eval { 'A' =~ $dynamic_missing ? 1 : 0 };
like($@, qr/main::IsA24DynamicMissing/,
    'unknown nested dynamic property reports its qualified name on reach');
ok(!defined($dynamic_reached), 'unknown nested dynamic property aborts matching');

{
    package A24DeferredPackage;
    our $package_regex = qr/^(??{ '\\p{InA24Local}' })$/;
    sub InA24Local { "0041\n" }
}
{
    package A24CallerPackage;
    sub InA24Local { "0042\n" }
    sub run { 'B' =~ $A24DeferredPackage::package_regex ? 1 : 0 }
}
ok(A24CallerPackage::run(),
    'nested dynamic property uses the package active at the match call site');

our ($sticky_construct_calls, $sticky_forward_calls, $sticky_forward);
sub IsA24StickyConstruct {
    ++$sticky_construct_calls;
    die "sticky-construct-$sticky_construct_calls\n" if $sticky_construct_calls == 1;
    return "0041\n";
}
my $sticky_source = '\\p{IsA24StickyConstruct}';
my $sticky_construct_one = eval { qr/$sticky_source/ };
like($@, qr/sticky-construct-1/, 'defined construction failure is reported');
my $sticky_construct_two = eval { qr/$sticky_source/ };
like($@, qr/sticky-construct-1/, 'defined construction failure is sticky');
is($sticky_construct_calls, 1, 'sticky construction failure does not recall callback');
ok(!defined($sticky_construct_one) && !defined($sticky_construct_two),
    'sticky construction attempts both fail');

BEGIN { $sticky_forward = qr/^\p{IsA24StickyForward}$/ }
sub IsA24StickyForward {
    ++$sticky_forward_calls;
    die "sticky-forward-$sticky_forward_calls\n" if $sticky_forward_calls == 1;
    return "0042\n";
}
my $sticky_forward_one = eval { 'B' =~ $sticky_forward ? 1 : 0 };
like($@, qr/sticky-forward-1/, 'defined deferred failure is reported');
my $sticky_forward_two = eval { 'B' =~ $sticky_forward ? 1 : 0 };
like($@, qr/sticky-forward-1/, 'defined deferred failure is sticky');
is($sticky_forward_calls, 1, 'sticky deferred failure does not recall callback');
ok(!defined($sticky_forward_one) && !defined($sticky_forward_two),
    'sticky deferred attempts both abort matching');

done_testing;
