use strict;
use warnings;
use Scalar::Util qw(weaken);

our @weak_references;

sub capture_weak_reference {
    my ($index, $value) = @_;
    $weak_references[$index] = $value;
    weaken($weak_references[$index]);
}

sub replace_argument_by_index {
    $_[0] = bless {}, 'Issue2043::ByIndex';
    capture_weak_reference(0, $_[0]);
}

sub replace_argument_by_foreach_alias {
    for my $value (@_) {
        $value = bless {}, 'Issue2043::ByForeachAlias';
        capture_weak_reference(1, $value);
    }
}

sub replace_argument_and_return {
    $_[0] = bless {}, 'Issue2043::ReturnedTemporary';
    capture_weak_reference(2, $_[0]);
    return $_[0];
}

sub pass_temporary_to_index_assignment {
    replace_argument_by_index({});
}

sub pass_temporary_to_foreach_assignment {
    replace_argument_by_foreach_alias({});
}

use Test::More;

pass_temporary_to_index_assignment();
pass_temporary_to_foreach_assignment();
my $returned_temporary = replace_argument_and_return({});

is(scalar @weak_references, 3, 'captured all three overwritten temporary arguments');
ok(!defined $weak_references[0],
    'temporary argument overwritten through $_[0] is released after the call');
ok(!defined $weak_references[1],
    'temporary argument overwritten through a foreach alias is released after the call');
ok(defined $weak_references[2],
    'a temporary argument returned by the call stays alive for its caller');
undef $returned_temporary;
ok(!defined $weak_references[2],
    'the returned temporary argument is released when its caller drops it');

done_testing;
