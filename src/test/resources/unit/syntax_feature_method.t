use strict;
use warnings;
no warnings 'once';
use Test::More;

BEGIN {
    require './src/main/perl/lib/syntax.pm';
    require './src/main/perl/lib/Syntax/Feature/Method.pm';
    $INC{'syntax.pm'} = 'src/main/perl/lib/syntax.pm';
    $INC{'Syntax/Feature/Method.pm'} = 'src/main/perl/lib/Syntax/Feature/Method.pm';
}

my $loaded = eval q{
    use syntax qw(method);
    package SyntaxFeatureMethodTest;
    method total ($amount = 2) { $self->{base} + $amount }
    method current { $self->{base} }
    our $anonymous = method { $self->{base} + 3 };
    package main;
    1;
};

if (!$loaded && $^X !~ /jperl-exec|jperl/i
        && $@ =~ /syntax error|Bareword|method/i) {
    plan skip_all => 'system Perl does not provide Syntax::Feature::Method syntax';
}
die $@ unless $loaded;

my $object = bless { base => 40 }, 'SyntaxFeatureMethodTest';
is($object->total, 42, 'method declaration uses an implicit invocant and default');
is($object->total(3), 43, 'method declaration receives explicit arguments');
is($object->current, 40, 'method declaration without parentheses binds an invocant');
is($SyntaxFeatureMethodTest::anonymous->($object), 43,
    'anonymous method expression without parentheses binds an invocant');

done_testing();
