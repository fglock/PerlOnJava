use strict;
use warnings;
use Test::More;
require './src/main/perl/lib/App/Cpan.pm';

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

    is(
        App::Cpan::_cpanpm_status_indicates_failure(
            'A/AU/AUTHOR/Optional.tar.gz',
        ),
        App::Cpan::A_MODULE_FAILED_TO_INSTALL(),
        'failure of the explicitly requested distribution fails the command',
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
