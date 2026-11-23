use strict;
use warnings;
use Test::More;

our $nested_arity_line;
my $loaded = eval q{
    use Function::Parameters {
        fun => { defaults => 'function_strict' },
    };

    fun pair($left, $right) { $left + $right }
    fun two_named(:$first, :$second) { "$first:$second" }
    fun required_named(:$required) { $required }
    $nested_arity_line = __LINE__ + 1;
    fun call_pair_with_too_few() { pair(1) }
    1;
};
if (!$loaded && $@ =~ /Can't locate Function\/Parameters\.pm/) {
    plan skip_all => 'Function::Parameters is not installed in system Perl';
}
die $@ unless $loaded;

my $direct_arity_line = __LINE__ + 1;
my $direct_arity_result = eval { pair(1); 1 };
ok(!$direct_arity_result, 'a direct call with too few arguments fails');
like($@, qr/Too few arguments for fun pair \(expected 2, got 1\) at .* line $direct_arity_line\./,
    'direct arity errors use the call-site location');

my $nested_arity_result = eval { call_pair_with_too_few(); 1 };
ok(!$nested_arity_result, 'a nested call with too few arguments fails');
like($@, qr/Too few arguments for fun pair \(expected 2, got 1\) at .* line $nested_arity_line\./,
    'nested arity errors use the failing call expression location');

my $too_few_named = eval { two_named(huh => 1); 1 };
ok(!$too_few_named, 'missing required named arguments are detected with an unknown key');
like($@, qr/Too few arguments for fun two_named/,
    'insufficient named arguments retain the Function::Parameters arity error');

my $missing_named = eval { required_named(a => 1); 1 };
ok(!$missing_named, 'missing required names are detected before unknown names');
like($@, qr/In fun required_named: missing named parameter: required/,
    'missing names use the Function::Parameters diagnostic');

my $unknown_named = eval { required_named(required => 1, extra => 2); 1 };
ok(!$unknown_named, 'unknown names are rejected after required names are supplied');
like($@, qr/In fun required_named: no such named parameter: extra/,
    'unknown names use the Function::Parameters diagnostic');

my $odd_named = eval { required_named(a => 1, 'dangling'); 1 };
ok(!$odd_named, 'odd named argument lists are rejected');
like($@, qr/Odd number of paired arguments for fun required_named/,
    'odd named argument lists use the Function::Parameters diagnostic');

my $short_odd_named = eval { required_named('dangling'); 1 };
ok(!$short_odd_named, 'a short odd list with required named parameters fails arity validation');
like($@, qr/Too few arguments for fun required_named/,
    'too-few arity takes precedence for short odd named argument lists');

done_testing;
