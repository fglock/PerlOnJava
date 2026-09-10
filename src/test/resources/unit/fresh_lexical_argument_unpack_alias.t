use strict;
use warnings;
use Test::More tests => 3;

sub unpack_then_update_argument {
    my ($local) = @_;
    $_[0] .= '-caller';
    return $local;
}

my $caller = 'original';
is(unpack_then_update_argument($caller), 'original',
    'fresh lexical unpack keeps the value before argument-frame mutation');
is($caller, 'original-caller',
    'argument frame remains aliased to the caller');

my $second = 'next';
is(unpack_then_update_argument($second), 'next',
    'a later unpack has independent fresh lexical storage');
