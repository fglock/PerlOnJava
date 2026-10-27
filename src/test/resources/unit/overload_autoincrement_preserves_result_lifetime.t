use strict;
use warnings;
no warnings 'numeric';
use Test::More;

{
    package Local::FormattedDate;
    use Scalar::Util qw(refaddr);
    our %FORMATS;

    use overload
        '+' => sub { _offset($_[0], $_[1]) },
        '-' => sub { _offset($_[0], -$_[1]) },
        '""' => sub {
            my ($self) = @_;
            return sprintf $FORMATS{refaddr($self)}, $$self;
        },
        fallback => 1;

    sub _offset {
        my ($self, $delta) = @_;
        my $result = bless \($$self + $delta), ref($self);
        $FORMATS{refaddr($result)} = $FORMATS{refaddr($self)};
        return $result;
    }

    sub set_format { $FORMATS{refaddr($_[0])} = $_[1] }

    sub DESTROY { delete $FORMATS{refaddr($_[0])} }
}

my $value = 20261101;
my $date = bless \$value, 'Local::FormattedDate';
$date->set_format('custom:%d');

++ $date;
is("$date", 'custom:20261102', 'preincrement keeps state on the overloaded result');

-- $date;
is("$date", 'custom:20261101', 'predecrement keeps state on the overloaded result');

$date++;
is("$date", 'custom:20261102', 'postincrement keeps state on the overloaded result');

$date--;
is("$date", 'custom:20261101', 'postdecrement keeps state on the overloaded result');

done_testing();
