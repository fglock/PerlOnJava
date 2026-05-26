use strict;
use warnings;
use Test::More;

use feature 'lexical_subs';
no warnings 'experimental::lexical_subs';

{
    package LexicalSubPrototypeRegistry;
    sub simple_lookup {
        my ($self, $name) = @_;
        return $self->{$name};
    }
}

my $registry = bless { ArrayLike => 1 }, 'LexicalSubPrototypeRegistry';
my sub t (;$) { @_ ? die 'unexpected arguments' : $registry }

ok(t->simple_lookup('ArrayLike'), 'lexical sub with prototype can be a method invocant');
ok(!t->simple_lookup('Missing'), 'method call result is returned normally');

done_testing;
