use strict;
use warnings;
use Test::More tests => 2;

our @trace;

{
    package EvalOurAliasPackage;
    eval q{
        push @trace, 'eval body';
        sub record_eval_body { push @trace, 'eval sub body' }
    };
    die $@ if $@;
    record_eval_body();
}

is_deeply(\@main::trace, ['eval body', 'eval sub body'],
    'eval source retains an outer our alias while compiling after a package change');
{
    no warnings 'once';
    is_deeply(\@EvalOurAliasPackage::trace, [],
        'eval source does not create a package-local copy of the outer our variable');
}
