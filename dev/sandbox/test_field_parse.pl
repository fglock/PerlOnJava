use v5.38;
use feature 'class';
no warnings 'experimental::class';

class TestFields {
    field $s :reader = "the scalar";
    field @a :reader = qw( the array );
    field %h :reader = qw( the hash );
}

1;
