use strict;
use warnings;
use Test::More tests => 3;

{
    package ByteAutoload;
    our $AUTOLOAD;
    sub AUTOLOAD {
        return if $AUTOLOAD =~ /::DESTROY\z/;
        my $class = ref $_[0];
        my $valid = q{[^:'[:cntrl:]]{0,1024}};
        return $AUTOLOAD =~ /^${class}::($valid)$/ ? $1 : undef;
    }
}

my $object = bless {}, 'ByteAutoload';
my $method = '§ߨ~ nobody+5@nowhere.net 䕨 has 64K €';
is($object->$method, $method, 'AUTOLOAD preserves byte method name');
is(unpack('H*', $object->$method), unpack('H*', $method),
    'AUTOLOAD preserves exact method-name octets');
is($object->${\100}, '100',
    'AUTOLOAD qualifies a numeric dynamic method with the invocant class');
