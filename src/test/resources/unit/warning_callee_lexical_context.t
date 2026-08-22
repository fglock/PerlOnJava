use strict;
use warnings;
use Test::More;
use lib 'src/test/resources/unit/lib';
use WarningContextCallee ();

sub invoke_capturing {
    my ($code) = @_;
    my @warnings;
    my ($ok, $error);
    {
        local $SIG{__WARN__} = sub { push @warnings, @_ };
        $ok = eval { $code->(); 1 };
        $error = $@;
    }
    return ($ok, $error, \@warnings);
}

my $disabled = \&WarningContextCallee::disabled;
my $enabled = \&WarningContextCallee::enabled;
my $fatal = \&WarningContextCallee::fatal;

{
    use warnings;
    my ($ok, $error, $warnings) = invoke_capturing($disabled);
    ok($ok, 'enabled caller does not make disabled callee fatal');
    is($error, '', 'enabled caller leaves disabled callee successful');
    is(scalar @$warnings, 0, 'enabled caller does not leak into disabled callee');
}

{
    no warnings;
    my ($ok, $error, $warnings) = invoke_capturing($enabled);
    ok($ok, 'disabled caller leaves enabled callee nonfatal');
    like($warnings->[0] // '', qr/Invalid conversion in sprintf/,
        'enabled callee warns under disabled caller');
    is(scalar @$warnings, 1, 'enabled callee emits exactly one warning');
}

{
    use warnings FATAL => 'printf';
    my ($ok, $error, $warnings) = invoke_capturing($disabled);
    ok($ok, 'fatal caller does not make disabled callee fatal');
    is($error, '', 'disabled callee ignores caller fatality');
    is(scalar @$warnings, 0, 'disabled callee remains warning-free under fatal caller');
}

{
    no warnings;
    my ($ok, $error, $warnings) = invoke_capturing($fatal);
    ok(!$ok, 'fatal callee dies under disabled caller');
    like($error, qr/Invalid conversion in sprintf/,
        'fatal callee reports printf failure');
    is(scalar @$warnings, 0, 'fatal warning is not delivered as a warning');
}

done_testing;
