use strict;
use warnings;
use Test::More tests => 1;

no warnings 'once';

sub chained_glob_code_alias_source {
    return 'chained code alias';
}

*chained_glob_code_alias_target = *chained_glob_code_alias_target
    = \&chained_glob_code_alias_source;

is(chained_glob_code_alias_target(), 'chained code alias',
    'a chained typeglob code alias remains callable');
