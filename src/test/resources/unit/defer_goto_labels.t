use strict;
use warnings;
use Test::More;
use feature 'defer';

ok(eval q{sub { defer { goto HERE; HERE: 1; } }; 1},
    'forward goto to a local defer label compiles');
ok(eval q{sub { defer { HERE: 1; goto HERE; } }; 1},
    'backward goto to a local defer label compiles');

my $error = eval q{
    sub {
        while (1) {
            goto HERE;
            defer { HERE: 1; }
        }
    }->();
    1;
} ? '' : $@;
like $error, qr/^Can't "goto" into a "defer" block/,
    'goto from outside cannot enter a defer block';

my @warnings;
my $defer_error;
{
    local $SIG{__WARN__} = sub { push @warnings, @_ };
    defer {
        eval 'last';
        $defer_error = $@;
    }
}
like $warnings[0] // '', qr/Exiting eval via last/,
    'loop control from eval in defer is diagnosed as exiting eval';
like $defer_error // '', qr/^Can't "last" out of a "defer" block/,
    'loop control from eval cannot escape its defer block';

my $called_sub_error = eval {
    sub {
        sub defer_escape { goto DEFER_ESCAPE_TARGET }
        defer { defer_escape() }
    }->();
    goto DEFER_ESCAPE_TARGET;
    DEFER_ESCAPE_TARGET: 1;
    1;
} ? '' : $@;
like $called_sub_error, qr/^Can't "goto" out of a "defer" block/,
    'a called sub cannot goto out of its caller defer block';

done_testing();
