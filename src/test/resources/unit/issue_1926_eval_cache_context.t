use strict;
use warnings;
use Test::More;
use attributes ();
use warnings 'ambiguous';
no warnings qw(redefine once);

our $scalar;
sub method_target :method { 1 }
my $value;
my @attribute_errors;
for $value (\&method_target, \$scalar) {
    eval "use attributes __PACKAGE__, \$value, 'method'";
    push @attribute_errors, $@;
}
is $attribute_errors[0], '', 'method is valid on a code reference';
like $attribute_errors[1], qr/^Invalid SCALAR attribute: method/,
    'same eval source validates the current reference type';

sub method_time :method { 1 }
sub plain_time { 1 }
my @warning_runs;
for my $sub (\&method_time, \&plain_time) {
    my @warnings;
    {
        local *time = $sub;
        local $SIG{__WARN__} = sub { push @warnings, $_[0] };
        eval '()=time';
    }
    push @warning_runs, \@warnings;
}
is scalar(@{$warning_runs[0]}), 0,
    'method attribute suppresses ambiguous core warning';
like join('', @{$warning_runs[1]}), qr/^Ambiguous call resolved as CORE::time\(\)/,
    'same eval source rechecks current symbol table';

done_testing;
