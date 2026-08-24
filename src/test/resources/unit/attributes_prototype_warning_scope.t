use strict;
use warnings;
use Test::More;

my @warnings;
{
    local $SIG{__WARN__} = sub { push @warnings, shift };
    eval q{
        package AttributePrototypeWarningScope;
        sub target(baz) {}
    };
    is $@, '', 'initial prototype declaration compiles';
    @warnings = ();

    eval q{
        package AttributePrototypeWarningScope;
        no warnings 'illegalproto';
        use attributes __PACKAGE__, \&target, 'prototype(new)';
    };
}

is prototype(\&AttributePrototypeWarningScope::target), 'new',
    'attributes pragma updates the prototype';
is scalar @warnings, 1,
    'disabled illegal prototype warning does not precede the mismatch warning';
like $warnings[0],
    qr/Prototype mismatch: sub AttributePrototypeWarningScope::target \(baz\) vs \(new\)/,
    'prototype mismatch warning remains visible';

done_testing;
