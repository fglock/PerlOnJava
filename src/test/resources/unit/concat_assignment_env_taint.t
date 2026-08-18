#!perl -T
use strict;
use warnings;
use Test::More;
use Scalar::Util qw(tainted);

my $tainted_empty = substr($^X, 0, 0);
ok(tainted($tainted_empty), 'the fixture obtains a tainted empty string');

{
    local $ENV{PATH} .= $tainted_empty;
    ok(tainted($ENV{PATH}),
        'local concat assignment preserves taint from the original PATH slot');

    SKIP: {
        skip 'system {} has different semantics on Win32', 1
            if $^O eq 'MSWin32';
        eval { system { 'echo' } '/arg0', 'arg1' };
        like($@, qr/^Insecure \$ENV/,
            'original PATH slot remains identifiable as tainted environment data');
    }
}

{
    local $ENV{PATH} = '/usr/bin';
    ok(!tainted($ENV{PATH}), 'constant assignment makes the localized PATH clean');

    $ENV{PATH} .= $tainted_empty;
    ok(tainted($ENV{PATH}), 'concat assignment propagates taint through %ENV');
    is($ENV{PATH}, '/usr/bin', 'concat assignment preserves the PATH value');

    SKIP: {
        skip 'system {} has different semantics on Win32', 1
            if $^O eq 'MSWin32';
        eval { system { 'echo' } '/arg0', 'arg1' };
        like($@, qr/^Insecure \$ENV\{PATH\}/,
            'process launch reports tainted PATH before directory validation');
    }
}

done_testing;
