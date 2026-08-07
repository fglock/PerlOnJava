use strict;
use warnings;
use Test::More;
use App::Cpan ();

{
    no warnings 'redefine';
    local *CPAN::Shell::find_failed = sub {
        return ([ 1, 'AUTHOR/Dependency.tar.gz', 'make_test', 'NO', 0, 1 ]);
    };
    is(
        App::Cpan::_cpanpm_status_indicates_failure(),
        App::Cpan::A_MODULE_FAILED_TO_INSTALL(),
        'mandatory prerequisite failure makes the command fail',
    );
}

{
    no warnings 'redefine';
    local *CPAN::Shell::find_failed = sub {
        return ([ 1, 'AUTHOR/Optional.tar.gz', 'make_test', 'NO', 0, 0 ]);
    };
    ok(
        !App::Cpan::_cpanpm_status_indicates_failure(),
        'optional recommendation failure does not fail the command',
    );
}

{
    no warnings 'redefine';
    local *App::Cpan::_get_cpanpm_last_line = sub {
        return "This option does not take effect\n";
    };
    ok(
        !App::Cpan::_cpanpm_output_indicates_failure(),
        q{an informational 'not' does not make a successful install fail},
    );
}

done_testing;
