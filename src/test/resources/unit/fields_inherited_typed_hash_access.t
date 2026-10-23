use strict;
use warnings;
use Test::More tests => 3;

{
    package Local::InheritedFields::Parent;
    use fields qw(cache key);

    sub new {
        my ($class, $cache, $key) = @_;
        return bless { cache => $cache, key => $key }, $class;
    }
}

{
    package Local::InheritedFields::Child;
    use base 'Local::InheritedFields::Parent';
    use fields qw(store_entry);

    sub inherited_cache {
        my Local::InheritedFields::Child $self = shift;
        return $self->{cache};
    }

    sub inherited_key {
        my Local::InheritedFields::Child $self = shift;
        return $self->{key};
    }
}

my $cache = bless {}, 'Local::InheritedFields::Parent';
my $child = Local::InheritedFields::Child->new($cache, 'entry-1');

is($child->inherited_cache, $cache, 'typed child accesses inherited cache field');
is($child->inherited_key, 'entry-1', 'typed child accesses inherited key field');
isa_ok($child, 'Local::InheritedFields::Child');
