use warnings;
use Test::More;

plan skip_all => 'try/catch/finally requires Perl 5.44'
    if $] < 5.044;
plan tests => 4;

my $warnings = '';
{
    local $SIG{__WARN__} = sub { $warnings .= shift };
    eval q{
        use feature 'try';
        try { 1 } catch ($e) { 1 } finally { 1 }
    };
}
like($warnings, qr/try\/catch\/finally is experimental/,
    'experimental try syntax warns during compilation');

my $error = eval q{use feature 'try'; try { 1 } catch { 1 }; 1;};
like($@, qr/^catch block requires a \(VAR\) at /,
    'catch requires an exception variable');

our $try_finally_calls = 0;
my $returning_sub = eval q{
    use feature 'try';
    sub {
        try { return 'try result' }
        catch ($e) {}
        finally { $main::try_finally_calls++ }
        return 'after try';
    }
};
is($returning_sub->(), 'try result', 'return in try exits the containing sub');
is($try_finally_calls, 1, 'finally runs before a return exits the sub');
