use strict;
use warnings;
use Test::More tests => 4;
use Scalar::Util qw(weaken);

my $hash = {};
$hash->{self} = $hash;
my $weak_hash = $hash;
weaken($weak_hash);
undef $hash;
ok(defined $weak_hash,
    'an unblessed hash cycle retains its weak observer after root release');
$weak_hash->{self} = undef;
ok(!defined $weak_hash,
    'removing the final strong hash edge clears the weak observer');

my $array = [];
push @$array, $array;
my $weak_array = $array;
weaken($weak_array);
undef $array;
ok(defined $weak_array,
    'an unblessed array cycle retains its weak observer after root release');
$weak_array->[0] = undef;
ok(!defined $weak_array,
    'removing the final strong array edge clears the weak observer');
