use strict;
use warnings;
use Test::More;

{
    package Local::ClosureOwnerProbe;
    our $destroy_count = 0;
    our $destroyed_while_argument_live = -1;

    sub new {
        return bless { token => 'alive' }, shift;
    }

    sub DESTROY {
        ++$destroy_count;
    }
}

sub read_after_capture_scope_exits {
    {
        my $captured = $_[0];
        my $callback = sub { $captured->{token} };
    }

    $Local::ClosureOwnerProbe::destroyed_while_argument_live =
        $Local::ClosureOwnerProbe::destroy_count;
    return $_[0]->{token};
}

my $value = read_after_capture_scope_exits(Local::ClosureOwnerProbe->new);
is($Local::ClosureOwnerProbe::destroyed_while_argument_live, 0,
    'the active call argument keeps its referent alive after the captured lexical leaves scope');
is($value, 'alive', 'the active call argument remains usable');
is($Local::ClosureOwnerProbe::destroy_count, 1,
    'the temporary referent is destroyed after the call returns');

done_testing;
