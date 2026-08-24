use strict;
use warnings;
use Test::More tests => 3;

{
    package Local::Inline;

    my $prefix = __PACKAGE__ . '::';
    no strict 'refs';
    *{$prefix . 'import'} = sub {
        my ($class, $value) = @_;
        return "$class:$value";
    };

    my $original = \&import;
    no warnings 'redefine';
    *{$prefix . 'import'} = sub {
        shift if @_ == 3 && !$_[1];
        goto &$original;
    };
}

is(Local::Inline->import('direct'), 'Local::Inline:direct',
    'captured original coderef survives glob replacement');

my $method = Local::Inline->can('import');
is($method->('Local::Inline', 'coderef'), 'Local::Inline:coderef',
    'replacement coderef tail-calls captured original');

is(Local::Inline->import('again'), 'Local::Inline:again',
    'captured target remains stable across calls');
