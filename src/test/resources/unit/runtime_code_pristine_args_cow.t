use strict;
use warnings;
use Test::More;

# caller() from package DB must expose the invocation-time aliases, even after
# the callee has shifted @_ before the debugger query. This is the semantic
# contract behind RuntimeCode's copy-on-write pristine-argument frames.
{
    package DB;
    sub snapshot_and_rewrite_caller_args {
        my ($depth) = @_;
        my @caller = caller($depth);
        my @args = @DB::args;
        $DB::args[0] = 'rewritten-through-db';
        return ($caller[3], \@args);
    }
}

sub shift_then_query_db_args {
    shift @_;
    return DB::snapshot_and_rewrite_caller_args(1);
}

my ($first, $second) = ('first', 'second');
my ($caller, $snapshot) = shift_then_query_db_args($first, $second);

is($caller, 'main::shift_then_query_db_args',
    'DB caller query selects the shifted callee frame');
is_deeply($snapshot, ['first', 'second'],
    '@DB::args retains the entry-time argument slots after shift @_');
is($first, 'rewritten-through-db',
    '@DB::args remains aliased to the original first argument');
is($second, 'second',
    'copy-on-write snapshot does not alter untouched argument aliases');

done_testing;
