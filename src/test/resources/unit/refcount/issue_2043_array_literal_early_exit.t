use strict;
use warnings;
use Test::More;

our @events;

{
    package Issue2043::FlushGuard;
    sub DESTROY { push @main::events, $_[0]{label} }
}

sub return_from_array_element {
    my $array = [ do { return 'escaped' } ];
    die 'array construction should have exited through return';
}

is(return_from_array_element(), 'escaped',
    'return in an array element exits its enclosing subroutine');

my $after = bless { label => 'later-local' }, 'Issue2043::FlushGuard';
undef $after;
is_deeply(\@events, ['later-local'],
    'later lifecycle flushes run after array construction exits early');

done_testing();
