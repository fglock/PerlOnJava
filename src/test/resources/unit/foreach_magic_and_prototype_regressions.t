use strict;
use warnings;
use feature qw(declared_refs refaliasing state);
no warnings qw(experimental::declared_refs experimental::refaliasing);
use Test::More;

{
    package Local::FetchStoreMonitor;
    our ($FETCHES, $STORES);

    sub TIESCALAR { bless { value => '' }, shift }
    sub FETCH { $FETCHES++; return $_[0]{value} }
    sub STORE { $STORES++; $_[0]{value} = $_[1] }
}

{
    tie my $value, 'Local::FetchStoreMonitor';
    $Local::FetchStoreMonitor::FETCHES = 0;
    $Local::FetchStoreMonitor::STORES = 0;
    my $assigned = $value = 'first';
    is($assigned, 'first', 'assignment expression returns the tied scalar value');
    is($Local::FetchStoreMonitor::FETCHES, 1, 'tied assignment fetches its value once');
    is($Local::FetchStoreMonitor::STORES, 1, 'tied assignment stores once');

    $Local::FetchStoreMonitor::FETCHES = 0;
    $Local::FetchStoreMonitor::STORES = 0;
    $assigned = $value = $value . 'x';
    is($assigned, 'firstx', 'chained tied concatenation returns the stored value');
    is($Local::FetchStoreMonitor::FETCHES, 2, 'chained tied concatenation fetches twice');
    is($Local::FetchStoreMonitor::STORES, 1, 'chained tied concatenation stores once');
}

{
    our @foreach_results;
    my $supports_declared_ref_iterator = eval q{
        use feature qw(declared_refs refaliasing);
        no warnings qw(experimental::declared_refs experimental::refaliasing);
        my $scalar;
        my @scalar_values;
        foreach my ($key, \$scalar) (one => \1, two => \2, three => \3) {
            push @scalar_values, "$key=$scalar";
        }
        push @foreach_results, join(',', @scalar_values);

        my %hash;
        my @hash_values;
        foreach my ($key, \%hash) (one => {1 => 1}, two => {2 => 2}) {
            push @hash_values, "$key=" . join('|', %hash);
        }
        push @foreach_results, join(',', @hash_values);

        my @array_values;
        foreach my ($key, \@array) (one => [1], two => [2, 2], three => [3, 3, 3]) {
            push @array_values, "$key=<@array>";
        }
        push @foreach_results, join(',', @array_values);

        my @first;
        my @second;
        for my ($left, $right) (\@first, \@second) {
            push @foreach_results, &Internals::SvREFCNT(\@first) + 1;
        }
        push @foreach_results, &Internals::SvREFCNT(\@first) + 1;
        1;
    };
    SKIP: {
        skip 'system Perl does not support declared-reference foreach variables', 5
            unless $supports_declared_ref_iterator;
        is($foreach_results[0], 'one=1,two=2,three=3',
            'multivariable foreach aliases declared scalar references');
        is($foreach_results[1], 'one=1|1,two=2|2',
            'multivariable foreach aliases declared hash references');
        is($foreach_results[2], 'one=<1>,two=<2 2>,three=<3 3 3>',
            'multivariable foreach aliases declared array references');
        is($foreach_results[3], 3, 'reference-valued loop variable retains its foreach alias');
        is($foreach_results[5], 2, 'reference-valued foreach alias is released after the loop');
    }
}

{
    ++$Dog::VERSION;
    is(eval q{for my Dog $spot ('Woof') { } 42}, 42,
        'typed lexical foreach variable accepts an existing package');
    is($@, '', 'typed lexical foreach variable has no error');
    is(eval q{for our Dog $spot ('Woof') { } 42}, 42,
        'typed package foreach variable accepts an existing package');
    is($@, '', 'typed package foreach variable has no error');

    for my $code (
        q{for our ($left, $right) (6, 9) {}},
        q{for CORE::my Dog $spot ('Woof') { } 42},
        q{for CORE::our Dog $spot ('Woof') { } 42},
        q{for CORE::state Dog $spot ('Woof') { } 42},
        q{for state Dog $spot ('Woof') { } 42},
    ) {
        eval $code;
        like($@, qr/^Missing \$ on loop variable/, "$code reports the loop-variable diagnostic");
    }
}

{
    my @warnings;
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    eval q{use warnings 'illegalproto'; my $sub = sub (x) {}};
    is(scalar @warnings, 1, 'illegal prototype emits one compile-time warning');
    like($warnings[0], qr/^Illegal character in prototype/,
        'illegal prototype warning reaches the Perl warning handler');

    @warnings = ();
    eval q{use warnings 'illegalproto'; my $sub = sub (@$) {}};
    is(scalar @warnings, 1, 'prototype after an at-sign emits one warning');
    like($warnings[0], qr/^Prototype after '\@' for/,
        'prototype after an at-sign warning reaches the Perl warning handler');
}

done_testing();
