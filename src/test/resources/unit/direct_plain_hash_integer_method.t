use strict;
use warnings;
use Test::More;

{
    package DirectPlainHashIntegerMethod;
    sub add {
        my ($self, $n) = @_;
        $self->{x} += $n;
        $self->{y} += $n;
        return $self->{x} + $self->{y};
    }
}

my $plain = bless { x => 1, y => 2 }, 'DirectPlainHashIntegerMethod';
is($plain->add(3), 9, 'plain native-integer method update');
is_deeply($plain, { x => 4, y => 5 }, 'plain method retains both updated slots');

my $large_one = bless { x => 4000, y => 5000 }, 'DirectPlainHashIntegerMethod';
my $large_two = bless { x => 4000, y => 5000 }, 'DirectPlainHashIntegerMethod';
my $large_result_one = $large_one->add(0);
my $large_result_two = $large_two->add(0);
is($large_result_one, 9000, 'large integer method result');
is($large_result_two, 9000, 'another large integer method result');
$large_result_one += 1;
is($large_result_one, 9001, 'large integer method result remains writable');
is($large_result_two, 9000, 'updating one large result leaves another unchanged');

{
    package DirectPlainHashIntegerMethod::Tie;
    sub TIEHASH { bless { values => { x => 1, y => 2 }, stores => 0 }, shift }
    sub FETCH { $_[0]{values}{$_[1]} }
    sub STORE { $_[0]{stores}++; $_[0]{values}{$_[1]} = $_[2] }
    sub stores { $_[0]{stores} }
}

tie my %tied, 'DirectPlainHashIntegerMethod::Tie';
my $tied = bless \%tied, 'DirectPlainHashIntegerMethod';
is($tied->add(2), 7, 'tied hash receiver retains ordinary method semantics');
ok((tied(%tied))->stores >= 2, 'tied receiver performed its STORE callbacks');

{
    package DirectPlainHashIntegerMethod::Number;
    use overload '0+' => sub { $_[0]{value} }, fallback => 1;
}

my $overloaded = bless { x => 1, y => 2 }, 'DirectPlainHashIntegerMethod';
my $number = bless { value => 4 }, 'DirectPlainHashIntegerMethod::Number';
is($overloaded->add($number), 11, 'overloaded argument retains ordinary numeric dispatch');

done_testing;
