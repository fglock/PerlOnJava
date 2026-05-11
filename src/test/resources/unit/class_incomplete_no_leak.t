use v5.36;
use feature 'class';
no warnings 'experimental::class';
use Test::More tests => 4;

our $saw_end;

BEGIN {
    eval <<'CLASS';
class IncompleteNoLeak {
    field $old :reader;
    method stale() { "old" }
    BEGIN { ++$saw_end; }
# no }
CLASS
    ok($saw_end, 'saw the end of the incomplete class definition');
}

class IncompleteNoLeak {
    field $value = "new";
    method value() { $value }
}

my $obj = IncompleteNoLeak->new;
ok(!$obj->can("stale"), 'method from incomplete class definition was not registered');
ok(!$obj->can("old"), 'accessor from incomplete class definition was not registered');
is($obj->value, "new", 'replacement class method is registered');
