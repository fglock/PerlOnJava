use strict;
use warnings;
use Test::More;

my $called = 0;
&{sub { $called = 1 }};
is($called, 1, 'top-level &{sub {...}} invokes the anonymous coderef');

my $value = &{sub { 'value from anonymous coderef' }};
is($value, 'value from anonymous coderef',
    'top-level &{sub {...}} returns the anonymous coderef value');

my $reference = \&{sub { 'reference-only anonymous coderef' }};
is(ref($reference), 'CODE', '\\&{sub {...}} takes an anonymous coderef');
is($reference->(), 'reference-only anonymous coderef',
    '\\&{sub {...}} defers invocation until called');

done_testing;
