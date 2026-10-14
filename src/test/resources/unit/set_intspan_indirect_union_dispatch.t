use strict;
use warnings;
use Test::More;

{
    package Local::SetIntSpanLike;

    use overload 'ge' => sub { $_[2] ? 1 : 0 }, fallback => 1;

    sub new { bless { calls => [] }, shift }
    sub union {
        my ($self, @args) = @_;
        push @{ $self->{calls} }, \@args;
        return $self;
    }
}

my $set = Local::SetIntSpanLike->new;
my $set_1 = union $set;
my $set_2 = union $set "1-5,8-9";
my $set_3 = union $set $set_2;
my $set_4 = union $set +[1, 5, 2, 8, 9, 1, 3, 4, 9];

is($set_2, $set, 'string-form union uses indirect-object dispatch');
is($set_3, $set, 'object-form union uses indirect-object dispatch');
is($set_4, $set, 'array-reference union uses indirect-object dispatch');
is_deeply($set->{calls}, [
    [],
    ['1-5,8-9'],
    [$set],
    [[1, 5, 2, 8, 9, 1, 3, 4, 9]],
], 'Set::IntSpan-style union calls retain their arguments');
ok('3-8' ge $set, 'interpreter string comparisons dispatch to right-hand overloads');

done_testing;
