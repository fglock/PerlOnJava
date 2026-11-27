use strict;
use warnings;
use Test::More tests => 4;

my $callback = sub { 'before' };
sub fetch_callback { return $callback }
sub replace_callback { $callback = sub { 'after' }; return 1 }
sub invoke_first { return $_[0]->() }

is(invoke_first(fetch_callback(), replace_callback()), 'before',
    'a returned coderef is copied before the next argument changes its source');
is($callback->(), 'after', 'the source coderef remains independently writable');

$callback = sub { 'list-before' };
sub fetch_pair { return ($callback, $callback) }
sub replace_pair { $callback = sub { 'list-after' }; return 1 }
sub invoke_pair { return join '/', $_[0]->(), $_[1]->() }

is(invoke_pair(fetch_pair(), replace_pair()), 'list-before/list-before',
    'each returned list element keeps its value through later argument evaluation');
is($callback->(), 'list-after', 'list return copies do not alias the source cell');
