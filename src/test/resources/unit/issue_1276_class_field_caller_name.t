use strict;
use warnings;
use Test::More;

sub capture_generated_accessor_name {
    return (caller(1))[3];
}

my $accessor = do {
    package Class::Field;
    eval q{
        sub {
            local *__ANON__ = 'Foo::x';
            main::capture_generated_accessor_name();
        }
    } or die $@;
};

my $unqualified_accessor = do {
    package Class::Field;
    eval q{
        sub {
            local *__ANON__ = 'x';
            main::capture_generated_accessor_name();
        }
    } or die $@;
};

is($accessor->(), 'Foo::x',
    'caller reports the generated accessor name without its generator package');
is($unqualified_accessor->(), 'Class::Field::x',
    'caller keeps unqualified anonymous names relative to their package');

done_testing;
