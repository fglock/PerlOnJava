use strict;
use warnings;

use Scalar::Util qw(refaddr);
use Sub::Util qw(set_subname subname);
use Test::More;

my $code = sub { 42 };
{
    no strict 'refs';
    *{'A127::installed'} = $code;
}
my $glob_value = *A127::installed;

is(ref($glob_value), '', 'typeglob assignment returns a magical glob value');
is(A127::installed(), 42, 'glob value retains its installed CODE slot');

my $renamed = eval {
    set_subname('A127::renamed', $glob_value);
};
is($@, '', 'set_subname accepts a glob value with a CODE slot');
is($renamed, $glob_value, 'set_subname returns the original glob value');
is(subname(A127->can('installed')), 'A127::renamed',
    'set_subname renames the CODE slot selected through the glob');
is(refaddr(A127->can('installed')), refaddr($code),
    'renaming through the glob preserves CODE identity');

done_testing;
