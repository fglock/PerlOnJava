use strict;
use warnings;
use Test::More tests => 4;

my $compiled = eval q{sub unused_loop_control { next }; 1};
ok($compiled, 'an unused subroutine containing next compiles');

sub skip_iteration { next }
sub stop_iteration { last }
my @visited;
{
    no warnings 'exiting';
    for (1 .. 3) {
        push @visited, $_;
        skip_iteration();
        push @visited, 'unreachable';
    }
}
is("@visited", '1 2 3', 'next in a subroutine targets the active caller loop');

@visited = ();
{
    no warnings 'exiting';
    for (1 .. 3) {
        push @visited, $_;
        stop_iteration();
    }
}
is("@visited", '1', 'last in a subroutine targets the active caller loop');

my $escaped;
{
    no warnings 'exiting';
    OUTER: {
        my $callback = sub { last OUTER };
        eval { $callback->() };
        $escaped = 0;
    }
}
ok(!defined $escaped, 'labeled last crosses a callback and eval boundary');
