use strict;
use warnings;
use feature 'refaliasing';
no warnings 'experimental::refaliasing';
use Scalar::Util qw(weaken);
use Test::More;

{
    package RefaliasArrayCellOwnerMutationTarget;
    our %destroyed;
    sub DESTROY { ++$destroyed{$_[0]{id}} }
}

my (@deleted, @replaced, @spliced);
my ($weak_deleted, $weak_replaced, $weak_spliced);
my %observed;

{
    my $object = bless {id => 'deleted'}, 'RefaliasArrayCellOwnerMutationTarget';
    $weak_deleted = $object;
    weaken($weak_deleted);
    \(@deleted) = \($object);
}
$observed{delete_kept_cell} = defined $weak_deleted;
$observed{delete_alive_before} =
    ($RefaliasArrayCellOwnerMutationTarget::destroyed{deleted} // 0) == 0;
delete $deleted[0];
$observed{delete_released} = !defined $weak_deleted;
$observed{delete_destroyed_once} =
    ($RefaliasArrayCellOwnerMutationTarget::destroyed{deleted} // 0) == 1;

{
    my $object = bless {id => 'replaced'}, 'RefaliasArrayCellOwnerMutationTarget';
    $weak_replaced = $object;
    weaken($weak_replaced);
    \(@replaced) = \($object);
}
$observed{replacement_kept_cell} = defined $weak_replaced;
$observed{replacement_alive_before} =
    ($RefaliasArrayCellOwnerMutationTarget::destroyed{replaced} // 0) == 0;
{
    my $replacement = 1;
    \($replaced[0]) = \($replacement);
}
$observed{replacement_released} = !defined $weak_replaced;
$observed{replacement_destroyed_once} =
    ($RefaliasArrayCellOwnerMutationTarget::destroyed{replaced} // 0) == 1;

{
    my $object = bless {id => 'spliced'}, 'RefaliasArrayCellOwnerMutationTarget';
    $weak_spliced = $object;
    weaken($weak_spliced);
    \(@spliced) = \($object);
}
$observed{splice_kept_cell} = defined $weak_spliced;
$observed{splice_alive_before} =
    ($RefaliasArrayCellOwnerMutationTarget::destroyed{spliced} // 0) == 0;
splice @spliced, 0, 1;
$observed{splice_released} = !defined $weak_spliced;
$observed{splice_destroyed_once} =
    ($RefaliasArrayCellOwnerMutationTarget::destroyed{spliced} // 0) == 1;

ok($observed{delete_kept_cell}, 'delete case retains the aliased cell after lexical exit');
ok($observed{delete_alive_before}, 'delete target is not destroyed while the alias slot lives');
ok($observed{delete_released}, 'deleting the slot releases its aliased cell');
ok($observed{delete_destroyed_once}, 'delete destroys the referent exactly once');
ok($observed{replacement_kept_cell},
    'replacement case retains the aliased cell after lexical exit');
ok($observed{replacement_alive_before},
    'replacement target is not destroyed while the alias slot lives');
ok($observed{replacement_released},
    'replacing the slot releases its prior aliased cell');
ok($observed{replacement_destroyed_once},
    'replacement destroys the displaced referent exactly once');
ok($observed{splice_kept_cell}, 'splice case retains the aliased cell after lexical exit');
ok($observed{splice_alive_before}, 'splice target is not destroyed while the alias slot lives');
ok($observed{splice_released}, 'splice removal releases its aliased cell');
ok($observed{splice_destroyed_once}, 'splice destroys the referent exactly once');

done_testing;
