use strict;
use warnings;
use Test::More;
use Want qw(want);

sub called_in_boolean_context {
    return want('BOOL');
}

my $boolean_result = 0;
if (called_in_boolean_context()) {
    $boolean_result = 1;
}
is($boolean_result, 1, 'Want detects a call consumed as boolean');

sub called_in_core_boolean_context {
    return wantarray;
}
my $core_wantarray_result = 1;
if (called_in_core_boolean_context()) {
    $core_wantarray_result = 0;
}
is($core_wantarray_result, 1, 'wantarray still reports scalar in boolean context');

my $scalar_result = called_in_boolean_context();
ok(!$scalar_result, 'Want does not call ordinary scalar context boolean');

my $negated_result = 0;
if (!called_in_boolean_context()) {
    $negated_result = 1;
}
is($negated_result, 0, 'Want detects boolean context below logical not');

sub want_rreturn_exits_caller {
    if (want('SCALAR')) {
        Want::rreturn('returned from caller');
    }
    return 'fell through';
}
is(want_rreturn_exits_caller(), 'returned from caller',
    'Want::rreturn returns from the caller in scalar context');

sub want_style_return {
    my ($value, $success) = @_;
    if (want('LIST')) {
        Want::rreturn($value);
    } elsif (want('BOOL')) {
        Want::rreturn($success);
    } elsif (want('SCALAR')) {
        Want::rreturn($value);
    }
    return;
}
is(want_style_return('value', 1), 'value',
    'Want predicates preserve scalar return values');
ok(!want_style_return('unchanged', 0),
    'Want returns the false boolean result');
ok(!!want_style_return('changed', 1),
    'Want returns the true boolean result through double negation');

done_testing();
