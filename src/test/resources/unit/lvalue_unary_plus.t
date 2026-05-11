use strict;
use warnings;
use Test::More tests => 5;

my $value;

+sub :lvalue { return $value || $value }->() = 73;
is($value, 73, 'unary plus lvalue sub call assignment with explicit return');

+sub :lvalue { $value || $value }->() = 74;
is($value, 74, 'unary plus lvalue sub call assignment with implicit return');

(sub :lvalue { return $value || $value }->()) = 75;
is($value, 75, 'parenthesized lvalue sub call assignment still works');

our $slot = $value;

sub get_slot :lvalue { $slot }
sub id :lvalue { ${\shift} }

++id(get_slot);
is($slot, 76, 'prefix increment calls lvalue sub in scalar lvalue context');

my $old = id(get_slot)++;
is("$old,$slot", '76,77', 'postfix increment calls lvalue sub in scalar lvalue context');
