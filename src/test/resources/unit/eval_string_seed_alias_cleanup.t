use strict;
use warnings;
use Test::More tests => 3;
use Scalar::Util qw(weaken);

{
    package EvalStringSeedAliasCleanup::Cursor;
    sub DESTROY { $main::destroyed_cursor++ }
}

{
    my $x = 41;
    eval q{
        package EvalStringSeedAliasCleanup;
        sub probe { $x + 1 }
        1;
    } or die $@;
}

is(EvalStringSeedAliasCleanup::probe(), 42,
    'named sub defined in eval keeps captured lexical after seed alias cleanup');

our ($weak_cursor, $destroyed_cursor);
$destroyed_cursor = 0;

sub eval_with_cursor_lexical {
    my $cursor = bless {}, 'EvalStringSeedAliasCleanup::Cursor';
    $weak_cursor = $cursor;
    weaken($weak_cursor);
    eval q{ $cursor; 1 } or die $@;
}

eval_with_cursor_lexical();
Internals::jperl_gc() if defined &Internals::jperl_gc;

ok(!defined $weak_cursor,
    'eval seed alias does not keep direct eval lexical alive after scope exit');
is($destroyed_cursor, 1,
    'DESTROY fires for eval lexical after seed alias cleanup');
