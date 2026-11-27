use strict;
use warnings;
use feature 'refaliasing';
no warnings 'experimental::refaliasing';
use Scalar::Util qw(weaken);
use Test::More;

{
    package RefaliasArrayElementCellOwnerTarget;
    our %destroyed;
    sub DESTROY { ++$destroyed{$_[0]{id}} }
}

my @single_alias;
my $weak_single;
{
    my $source = bless { id => 'single' }, 'RefaliasArrayElementCellOwnerTarget';
    $weak_single = $source;
    weaken($weak_single);
    \($single_alias[0]) = \($source);
}
ok(defined $weak_single,
    'an array element refalias retains its source cell after lexical exit');
is($RefaliasArrayElementCellOwnerTarget::destroyed{single} // 0, 0,
    'the referent remains alive while its aliased array slot exists');
$single_alias[0] = undef;
ok(!defined $weak_single, 'replacing the final aliased slot releases its source cell');
is($RefaliasArrayElementCellOwnerTarget::destroyed{single}, 1,
    'the single-slot referent is destroyed exactly once');

my @duplicate_aliases;
my $weak_duplicate;
{
    my $object = bless { id => 'duplicate' }, 'RefaliasArrayElementCellOwnerTarget';
    my ($first_source, $second_source) = ($object, $object);
    $weak_duplicate = $object;
    weaken($weak_duplicate);
    \($duplicate_aliases[0]) = \($first_source);
    \($duplicate_aliases[1]) = \($second_source);
}
ok(defined $weak_duplicate, 'two array slots retain distinct source cells for the same referent');
$duplicate_aliases[0] = undef;
ok(defined $weak_duplicate, 'replacing one slot preserves the other alias');
$duplicate_aliases[1] = undef;
ok(!defined $weak_duplicate, 'replacing the last alias releases the source cell');
is($RefaliasArrayElementCellOwnerTarget::destroyed{duplicate}, 1,
    'the shared referent is destroyed exactly once');

done_testing;
