use strict;
use warnings;
use Test::More;

my $counter = 40;
sub increment_captured_counter { ++$counter }

is(increment_captured_counter(), 41,
    'named package sub captures a surrounding lexical');
is($counter, 41,
    'named package sub updates the original lexical cell');

my $STDERR;
sub duplicate_stderr_lexically {
    $STDERR ||= do {
        open my $copy, '>&', STDERR or die "Cannot duplicate STDERR: $!";
        $copy;
    };
}

my $stderr_copy = duplicate_stderr_lexically();
ok($stderr_copy, 'named package sub initializes a special-named lexical');
is($STDERR, $stderr_copy,
    'named package sub updates the original special-named lexical cell');
is(duplicate_stderr_lexically(), $stderr_copy,
    'named package sub reuses the captured lexical value');

my $stderr_scalar_slot = *main::STDERR{SCALAR};
ok(!defined $$stderr_scalar_slot,
    'captured lexical does not populate the STDERR glob scalar slot');

package NamedSubCapture::Globals;
our $value = 'global';

package main;
sub read_lexically_scoped_our { $value }

is(read_lexically_scoped_our(), 'global',
    'named package sub retains the package associated with our');
{
    local $NamedSubCapture::Globals::value = 'localized';
    is(read_lexically_scoped_our(), 'localized',
        'our remains a dynamic package lookup inside named package sub');
}

done_testing;
