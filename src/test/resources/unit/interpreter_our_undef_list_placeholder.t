use strict;
use warnings;
use Test::More;

our @values = qw(first second third);
our (undef, @values) = @values;

is_deeply(\@values, [qw(second third)],
    'our-list undef placeholder consumes the first assignment value');

our ($first, undef, $third) = qw(alpha ignored omega);
is($first, 'alpha', 'our-list preserves values before an undef placeholder');
is($third, 'omega', 'our-list preserves values after an undef placeholder');

(\my @sparse)->$#*++;
is(scalar @sparse, 1, 'last-index increment through a declared-array reference extends the array');
ok(!exists $sparse[0], 'last-index increment does not vivify the new array element');

our @loop_last_index;
for (scalar $#loop_last_index) {
    $_ = 3;
}
is($#loop_last_index, 3, 'foreach aliases a scalar array-last-index lvalue');

our @localized = qw(first second third);
{
    local @localized = @localized;
    local (undef, @localized) = @localized;
    is_deeply(\@localized, [qw(second third)],
        'nested local assignment retains its dynamically localized array');
}
is_deeply(\@localized, [qw(first second third)],
    'nested local assignment restores the outer array on scope exit');

my $filetest_fixture = 'build.gradle';
ok(-f -e $filetest_fixture, 'stacked file tests use the inner test stat cache');
ok(defined -s -f $filetest_fixture,
    'stacked file tests preserve a numeric result from the inner stat cache');

my $null_device = $^O eq 'MSWin32' ? 'NUL' : '/dev/null';
open(my $filetest_handle, '<', $null_device) or die "open $null_device: $!";
-e '__perlonjava_missing_filetest_cache_fixture__';
ok(!-e -t $filetest_handle,
    'stacked -e -t preserves the failed stat cache across the inner descriptor test');
-e $null_device;
ok(!-e -t $filetest_handle,
    'stacked -e -t invalidates a successful stat cache across the inner descriptor test');

{
    package Local::TiedCode;
    sub TIESCALAR { bless [ $_[1] ], $_[0] }
    sub FETCH { $_[0][0] }
    package main;
    tie my $tied_code, 'Local::TiedCode', sub { 41 };
    is(&$tied_code, 41, 'dynamic call through a tied scalar fetches its coderef once');
    no strict 'refs';
    local *1 = sub { 42 };
    tie my $tied_symbolic_code, 'Local::TiedCode', 1;
    is(&$tied_symbolic_code, 42,
        'dynamic call through a tied numeric symbolic coderef resolves its CODE slot');
}

my $postderef_source = 1;
my @postderef_refs = \($postderef_source = undef);
ok(!defined $postderef_refs[0]->$*,
    'postderef scalar through an array element dereferences the expression');

use Hash::Util ();
my %preallocated_hash;
keys %preallocated_hash = 64;
is(Hash::Util::num_buckets(%preallocated_hash), 128,
    'keys hash assignment preallocates hash buckets with Perl capacity rounding');

my %multidimensional_hash;
$multidimensional_hash{'left', 'right'} = 'combined';
is($multidimensional_hash{"left\034right"}, 'combined',
    'multidimensional hash assignment joins keys with the subscript separator');

{
    use feature 'refaliasing';
    no warnings 'experimental::refaliasing';
    my @aliased = (1, 2);
    \$aliased[1] = \42;
    is($aliased[1], 42, 'array-element reference aliasing replaces the lvalue slot');
}

{
    use feature 'refaliasing';
    no warnings 'experimental::refaliasing';
    our $declared_ref_source = 17;
    \(my $declared_ref_alias) = \$declared_ref_source;
    $declared_ref_alias = 23;
    is($declared_ref_source, 23,
        'declared-reference scalar alias writes through its source');
}

{
    no warnings 'numeric';
    is('a' x ('Inf' + 0), '', 'infinite positive repeat count is empty');
    is('a' x ('-Inf' + 0), '', 'infinite negative repeat count is empty');
}

{
    'abc' =~ /(b)/;
    my $iterations = 0;
    while (1) {
        'end' =~ /(end)/;
        redo if ++$iterations == 1;
        last;
    }
    is($1, 'b', 'redo through a regex loop restores the enclosing match state');
}

{
    'abc' =~ /(b)/;
    my $iterations = 0;
    while (1) {
        {
            'end' =~ /(end)/;
            redo if ++$iterations == 1;
        }
        last;
    }
    is($1, 'b', 'redo discards nested regex scopes before restoring the loop state');
}

{
    my $continued;
    our $interpreter_continue_local;
    local $interpreter_continue_local = 18;
    {
        local $interpreter_continue_local = 0;
    }
    continue {
        $continued = $interpreter_continue_local;
    }
    is($continued, 18, 'bare-block continue runs after its local scope is restored');
}

{
    sub interpreter_context_probe { $_[0] = wantarray; $_[1] }
    my $context = -1;
    my $loop_value = sub {
        my $counter = 1;
        while ($counter--) {
            interpreter_context_probe($context, 'body value');
        }
    };
    is(scalar($loop_value->()), 0, 'while returns its terminal false condition in scalar context');
    is($context, undef, 'while body executes in void context');
}

done_testing;
