use strict;
use warnings;
use Test::More;
use Tie::Array;
use Class::MethodMaker;

our @events;

{
    package GeneratedTieArrayHandler;
    use base 'Tie::StdArray';

    sub TIEARRAY { push @main::events, 'TIEARRAY'; $_[0]->SUPER::TIEARRAY }
    sub PUSH { push @main::events, 'PUSH'; $_[0]->SUPER::PUSH(@_[1 .. $#_]) }
    sub FETCH { push @main::events, 'FETCH'; $_[0]->SUPER::FETCH($_[1]) }
    sub DESTROY { push @main::events, 'DESTROY'; $_[0]->SUPER::DESTROY }
}

{
    package GeneratedTiedArrayOwner;
    Class::MethodMaker->import(
        [array => [{ -tie_class => 'GeneratedTieArrayHandler' }, 'items']]);
}

my $object = bless {}, 'GeneratedTiedArrayOwner';
$object->items_push(17);
is_deeply(\@events, [qw(TIEARRAY PUSH)],
          'generated tied field keeps its handler alive through first operation');
is($object->items_index(0), 17, 'generated tied field remains readable');
is_deeply(\@events, [qw(TIEARRAY PUSH FETCH)],
          'handler remains alive through fetch');
$object->items_reset;
is_deeply(\@events, [qw(TIEARRAY PUSH FETCH DESTROY)],
          'reset releases the handler after the field lifetime');

done_testing();
