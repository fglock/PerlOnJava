use strict;
use warnings;

sub End::DESTROY { $_[0]->() }

sub end (&) {
    my ($code) = @_;
    return bless($code, 'End');
}

sub ok {
    my ($passed, $name) = @_;
    ++$main::test_number;
    print(($passed ? 'ok' : 'not ok'), " $main::test_number - $name\n");
}

sub is {
    my ($got, $expected, $name) = @_;
    ok($got eq $expected, $name);
}

our $test_number = 0;
print "1..18\n";
foreach my $die_value ('', "aabbcc\n", [qw(aa bb cc)]) {
    foreach my $prior_error ('', "xxyyzz\n", [qw(xx yy zz)]) {
        my $warning = '';
        {
            local $SIG{__WARN__} = sub { $warning .= $_[0] };
            $@ = $prior_error;
            {
                my $guard = end { die $die_value if $die_value };
            }
            ok(
                ref($@) eq ref($prior_error) && $@ eq $prior_error,
                'localized $@ restores its previous value',
            );
        }
        $warning =~ s/ at [^\n]*\n\z//;
        is(
            $warning,
            $die_value ? "\t(in cleanup) $die_value" : '',
            'DESTROY preserves the expected cleanup warning',
        );
    }
}
