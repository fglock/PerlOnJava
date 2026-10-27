use strict;
use warnings;
use Scalar::Util qw(weaken);
use Test::More tests => 8;

our $destroyed = 0;
{
    package Local::ArrayReturnOwner;
    sub DESTROY { ++$main::destroyed }
}

sub return_local_array {
    my @items;
    push @items, $_[0];
    return @items;
}

{
    my $value = bless {}, 'Local::ArrayReturnOwner';
    my $weak = $value;
    weaken($weak);
    return_local_array($value);
    $value = undef;
    ok(!defined($weak), 'a discarded local array return releases its element owner');
    is($destroyed, 1, 'void return does not defer destruction past the last real owner');
}

{
    my $value = bless {}, 'Local::ArrayReturnOwner';
    my $weak = $value;
    weaken($weak);
    my $count = return_local_array($value);
    $value = undef;
    is($count, 1, 'a scalar array return reports its element count');
    ok(!defined($weak), 'a scalar array return retains no element owner');
    is($destroyed, 2, 'scalar return releases one additional object');
}

{
    my $value = bless {}, 'Local::ArrayReturnOwner';
    my $weak = $value;
    weaken($weak);
    my @returned = return_local_array($value);
    $value = undef;
    ok(defined($weak), 'a list return transfers ownership to the receiving array');
    @returned = ();
    ok(!defined($weak), 'clearing the received array releases its element owner');
    is($destroyed, 3, 'list return releases one additional object exactly once');
}
