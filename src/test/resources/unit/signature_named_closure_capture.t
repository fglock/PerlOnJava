use v5.40;
use Test::More;

my $value;
my sub populate_value { $value //= 42 }
my sub accept_named (%args) { return $args{name} }

sub import_value ($, @args) {
    populate_value() unless $value;
    accept_named(name => 'kept');
    return $value;
}

is import_value('ignored'), 42,
    'a signatured named sub retains its scalar capture beside lexical sub captures';

done_testing;
