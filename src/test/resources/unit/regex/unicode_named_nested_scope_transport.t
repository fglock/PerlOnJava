use strict;
use warnings;
use Test::More;
use lib 'src/test/resources/unit/lib';

sub compile_nested_custom_names {
    {
        use RegexImplementationCname;
        BEGIN { $RegexImplementationCname::Evil = 'A' }

        my $passthrough = qr/^\N{foo}[\N{B}\N{b}]$/;
        my $first = qr/^(\N{EVIL})$/;
        my $second = qr/^(\N{EVIL})$/;
        return ($passthrough, $first, $second);
    }
}

my ($passthrough, $first, $second) = compile_nested_custom_names();
ok('fooB' =~ $passthrough,
    'named-sub lexical charnames translator reaches a literal regex');
is($RegexImplementationCname::Evil, 'ABC',
    'each named-sub custom escape resolved once during source compilation');
ok('A' =~ $first && $1 eq 'A',
    'first named-sub custom expansion remains attached to its regex');
ok('AB' =~ $second && $1 eq 'AB',
    'identical source in the same nested scope resolves independently');
ok('A' =~ $first && $1 eq 'A',
    'later compilation does not contaminate the first nested regex');

my $outside = eval q{qr/^\N{foo}$/};
ok(!defined($outside) && $@ ne '',
    'custom translator does not leak outside its lexical block');

done_testing;
