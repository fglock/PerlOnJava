use strict;
use warnings;
use Test::More tests => 2;

sub write_through_caller_args {
    package DB;
    () = caller(0);
    $DB::args[0] = 'changed through caller alias';
}

my $value = 'before';
write_through_caller_args($value);

is($value, 'changed through caller alias',
    'caller exposes the original scalar slot');
is($DB::args[0], 'changed through caller alias',
    'caller retains the aliased scalar value');
