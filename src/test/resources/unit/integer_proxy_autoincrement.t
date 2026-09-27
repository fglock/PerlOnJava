use strict;
use warnings;
use Test::More;

use integer;

sub nested_slot {
    my ($state, $key) = @_;
    return $state->{stack}->[-1][1]{$key};
}

my $state = { stack => [ [ {}, {} ] ] };

is(++$state->{stack}->[-1][1]{pre_increment}, 1,
   'integer pre-increment vivifies and updates a nested hash lvalue');
is(nested_slot($state, 'pre_increment'), 1,
   'integer pre-increment persists in the nested hash slot');

is($state->{stack}->[-1][1]{post_increment}++, 0,
   'integer postfix increment returns zero for an undefined nested hash lvalue');
is(nested_slot($state, 'post_increment'), 1,
   'integer postfix increment persists in the nested hash slot');

is(--$state->{stack}->[-1][1]{pre_decrement}, -1,
   'integer pre-decrement vivifies and updates a nested hash lvalue');
is(nested_slot($state, 'pre_decrement'), -1,
   'integer pre-decrement persists in the nested hash slot');

my $post_decrement = $state->{stack}->[-1][1]{post_decrement}--;
ok(!defined $post_decrement,
   'integer postfix decrement returns undef for an undefined nested hash lvalue');
is(nested_slot($state, 'post_decrement'), -1,
   'integer postfix decrement persists in the nested hash slot');

done_testing;
