use strict;
use warnings;
use Test::More;

my $supports_finally = eval q{
    use feature 'try';
    no warnings 'experimental::try';
    my $error;
    try { 1 } catch ($error) {} finally { 1 }
    1;
};
plan skip_all => 'system Perl has no try/finally syntax' unless $supports_finally;

our ($finally_count, $reported_caller, $try_control_warnings);
$finally_count = 0;
my $definitions = q{
    use feature 'try';
    no warnings 'experimental::try';
    sub requested_try_finally_caller {
        $main::reported_caller = (caller 1)[3];
    }
    sub requested_try_finally_caller_wrapper {
        try { requested_try_finally_caller() }
        catch ($error) {}
        finally { ++$main::finally_count }
    }
    sub requested_try_finally_return {
        try { return 'inside try' }
        catch ($error) {}
        finally { ++$main::finally_count }
        return 'after try';
    }
    1;
};
eval $definitions or die $@;

requested_try_finally_caller_wrapper();
is($reported_caller, 'main::requested_try_finally_caller_wrapper',
    'try/finally does not add a caller frame');

my $try_control_code = q{
    use feature 'try';
    no warnings 'experimental::try';
    sub requested_try_control_caller { $main::reported_caller = (caller 1)[3] }
    sub requested_try_control_caller_wrapper {
        try { requested_try_control_caller() }
        catch ($error) {}
    }
    {
        my $warnings = '';
        local $SIG{__WARN__} = sub { $warnings .= $_[0] };
        { try { last } catch ($error) {} }
        { try { next } catch ($error) {} }
        my $count = 0;
        { try { ++$count; redo if $count < 2 } catch ($error) {} }
        REQUESTED_LAST: { try { last REQUESTED_LAST } catch ($error) {} }
        REQUESTED_NEXT: { try { next REQUESTED_NEXT } catch ($error) {} }
        $count = 0;
        REQUESTED_REDO: {
            try { ++$count; redo REQUESTED_REDO if $count < 2 }
            catch ($error) {}
        }
        requested_try_control_caller_wrapper();
        $main::try_control_warnings = $warnings;
    }
    1;
};
eval $try_control_code or die $@;
is($reported_caller, 'main::requested_try_control_caller_wrapper',
    'labeled last and next leave no try frame in caller()');
is($try_control_warnings, '', 'loop controls inside try produce no warnings');

is(requested_try_finally_return(), 'inside try',
    'return inside try/finally returns from the containing subroutine');
is($finally_count, 2, 'finally runs on normal completion and return');

my $warning = '';
{
    local $SIG{__WARN__} = sub { $warning .= shift };
    eval q{
        use feature 'try';
        my $error;
        try { 1 } catch ($error) {} finally { 1 }
    };
}
like($warning, qr/^try\/catch\/finally is experimental at /m,
    'try/finally emits its compile-time experimental warning');

eval q{
    use feature 'try';
    no warnings 'experimental::try';
    try { 1 } catch { 2 }
};
like($@, qr/^catch block requires a \(VAR\) at /,
    'catch without a variable reports the dedicated syntax error');

done_testing();
