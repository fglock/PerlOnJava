use strict;
use warnings;
use Test::More tests => 1;

{
    package Local::TailcallCompare;
    use overload 'cmp' => \&_string_compare, fallback => 1;

    sub new { bless { value => $_[1] }, $_[0] }

    sub _string_compare {
        my ($left, $right) = @_;
        my $method = $left->can('_compare');
        goto $method;
    }

    sub _compare {
        return $_[0]{value} cmp $_[1]{value};
    }
}

my @values = map { Local::TailcallCompare->new($_) } qw(c a b);
my @sorted = sort { $a cmp $b } @values;
is_deeply([map { $_->{value} } @sorted], [qw(a b c)],
    'an overloaded comparison can tailcall from a sort block');
