use strict;
use warnings;
use Test::More tests => 4;
use Scalar::Util qw(weaken);

{
    package LocalizedHashReturn;
    our @destroyed;

    sub new { bless { value => 1 }, shift }
    sub DESTROY { push @destroyed, 'destroyed' }
}

our %state = (slot => 'original');

sub transaction {
    local $state{slot} = 'temporary';
    my $value = LocalizedHashReturn->new;
    return $value;
}

my $returned = transaction();
is scalar @LocalizedHashReturn::destroyed, 0,
    'localized hash restoration preserves a returned object';

my $weak = $returned;
weaken($weak);
isa_ok $weak, 'LocalizedHashReturn', 'the returned object remains live';

undef $returned;
is scalar @LocalizedHashReturn::destroyed, 1,
    'the object is destroyed after its final owner is released';
ok !defined($weak), 'the weak observer clears at final destruction';
