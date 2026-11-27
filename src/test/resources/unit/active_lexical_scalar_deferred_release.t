#!/usr/bin/env perl
use strict;
use warnings;

use Scalar::Util qw(weaken);
use Test::More;

{
    package ActiveLexicalScalarDeferredObject;
    our $destroy_count = 0;
    sub DESTROY { ++$destroy_count }
}

my $weak;
{
    my $cell = 37;
    my $object = bless \$cell, 'ActiveLexicalScalarDeferredObject';
    $weak = $object;
    weaken($weak);
    my @owners = ($object);

    undef $object;
    ok(defined($weak), 'the aggregate slot retains the scalar-cell referent');

    delete $owners[0];

    ok(defined($weak), 'deleting the final counted edge waits for lexical-cell release');
    is($$weak, 37, 'the weak reference can still read the live scalar cell');
    is($ActiveLexicalScalarDeferredObject::destroy_count, 0,
        'DESTROY waits until the lexical scalar cell leaves scope');
}

ok(!defined($weak), 'the final lexical owner release clears the weak observer');
is($ActiveLexicalScalarDeferredObject::destroy_count, 1,
    'the scalar referent is destroyed once after lexical scope exit');

done_testing();
