use strict;
use warnings;
use Test::More tests => 1;

{
    package Type::Library;

    sub setup_type_library {
        my ($class, $type_library) = @_;
        no strict 'refs';
        push @{"${type_library}::ISA"}, $class;

        my $wrapper = eval "sub { package $type_library; &Type::Utils::extends; }";
        die $@ if $@;
        $wrapper->();
    }
}

{
    package Type::Utils;

    sub extends {
        die "Not a type library" unless caller->isa('Type::Library');
    }
}

{
    package Issue1744::Types;

    BEGIN { Type::Library->setup_type_library(__PACKAGE__); }
}

ok(Issue1744::Types->isa('Type::Library'),
    'extends sees the @ISA installed during type library setup');
