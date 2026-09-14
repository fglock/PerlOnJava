use strict;
use warnings;
use Test::More;

BEGIN {
    eval {
        require Devel::LexAlias;
        Devel::LexAlias->import('lexalias');
        1;
    } or plan skip_all => 'requires PerlOnJava Devel::LexAlias support';
}

{
    package FreshLexicalAliasTie;

    sub TIESCALAR { bless { value => $_[1], stores => $_[2] }, $_[0] }
    sub FETCH { $_[0]{value} }
    sub STORE { $_[0]{value} = $_[1]; ++${$_[0]{stores}} }
}

sub unpack_into_aliased_lexical {
    my ($value) = @_;
    return $value;
}

my $stores = 0;
tie my $aliased, 'FreshLexicalAliasTie', 'before', \$stores;
lexalias(\&unpack_into_aliased_lexical, '$value', \$aliased);

is(unpack_into_aliased_lexical('after'), 'after',
    'fresh lexical argument unpack reads the assigned tied alias');
is($aliased, 'after',
    'fresh lexical argument unpack assigns through the LexAlias destination');
ok($stores >= 1,
    'tied LexAlias destination receives a STORE through generic assignment');

done_testing;
