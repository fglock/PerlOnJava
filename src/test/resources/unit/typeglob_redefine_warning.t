use strict;
use warnings;
use Test::More;

{
    no strict 'refs';
    use warnings FATAL => 'redefine';

    my $first = sub { 'first' };
    *runtime_redefined = $first;

    my $same_error = do {
        local $@;
        eval { *runtime_redefined = $first };
        $@;
    };
    is($same_error, '', 'reinstalling the same coderef does not warn');

    my $replacement = sub { 'replacement' };
    my $replace_error = do {
        local $@;
        eval { *runtime_redefined = $replacement };
        $@;
    };
    like($replace_error, qr/^Subroutine main::runtime_redefined redefined/, 'a different coderef honors fatal redefine warnings');
    is(runtime_redefined(), 'first', 'fatal redefine leaves the original coderef installed');
}

{
    no strict 'refs';
    use warnings FATAL => 'redefine';

    use constant exported_constant => 'same value';
    *constant_alias = \&exported_constant;

    my $reexport_error = do {
        local $@;
        eval { *constant_alias = \&exported_constant };
        $@;
    };
    is(
        $reexport_error,
        '',
        're-exporting the same underlying constant does not warn',
    );
}

BEGIN {
    package FatalRedefinePragma;
    sub import {
        warnings->import(FATAL => qw(uninitialized numeric redefine));
    }
    $INC{'FatalRedefinePragma.pm'} = __FILE__;
}

{
    use FatalRedefinePragma;
    no strict 'refs';

    sub replace_from_pragma_scope {
        *pragma_redefined = sub { 'first' };
        *pragma_redefined = sub { 'replacement' };
    }

    my $pragma_error = do {
        local $@;
        eval { replace_from_pragma_scope() };
        $@;
    };
    like(
        $pragma_error,
        qr/^Subroutine main::pragma_redefined redefined/,
        'warning pragmas propagate fatal categories into their caller compile scope',
    );
}

done_testing;
