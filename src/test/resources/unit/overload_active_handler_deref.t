use strict;
use warnings;
use Test::More tests => 5;

{
    package ActiveArrayOverload;
    no overloading;
    our $calls = 0;
    use overload '@{}' => sub {
        my ($self) = @_;
        ++$calls;
        return [ @$self ];
    }, fallback => 1;
}

my $view = bless [qw(first second)], 'ActiveArrayOverload';
my @values = @$view;
is scalar(@values), 2, 'active @{} handler can dereference its own object';
is_deeply \@values, [qw(first second)], 'underlying array values are preserved';
is $ActiveArrayOverload::calls, 1, 'external dereference dispatches the overload once';
my @again = @$view;
is_deeply \@again, [qw(first second)], 'subsequent dereferences still use the overload';
is $ActiveArrayOverload::calls, 2, 'active-handler guard is cleared after the call returns';
