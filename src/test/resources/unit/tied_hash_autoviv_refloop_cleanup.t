use strict;
use warnings;
use Test::More tests => 6;
use Tie::Hash;

our $destroyed = 0;

{
    package THR::Client;
    use Tie::Hash;
    our @ISA = qw(Tie::StdHash);

    sub new { bless {}, shift }

    sub outer {
        my $self = shift;
        return $self if tied(%$self);
        my %outer;
        tie %outer, ref($self), $self;
        ++$self->{refcnt};
        bless \%outer, ref($self);
    }

    sub inner { tied(%{$_[0]}) || $_[0] }
    sub TIEHASH { $_[1] }

    sub drop_conn {
        my ($self, $err) = @_;
        if (my $msgs = delete $self->{msgs}) {
            $_->{error} = $err for values %$msgs;
        }
    }

    sub DESTROY {
        my $self = shift;
        ++$main::destroyed unless tied(%$self);
        my $inner = tied(%$self) or return;
        $inner->drop_conn(7) unless --$inner->{refcnt};
    }

    package THR::Message;

    sub new {
        my ($class, $parent) = @_;
        bless { parent => $parent->inner, id => 1 }, $class;
    }

    sub code { shift->{error} || 0 }
}

{
    my $client = THR::Client->new->outer;
    $client->{msgs}->{1} = THR::Message->new($client);
    ok(exists $client->inner->{msgs}, 'tied outer autovivifies into inner object');
}
ok($destroyed, 'inner object destroyed after internal loop is dropped');

my ($ref, $msg);
$destroyed = 0;
{
    my $client = THR::Client->new->outer;
    $msg = THR::Message->new($client);
    $client->{msgs}->{1} = $msg;
    $ref = $client->inner->outer;
    ok($ref, 'extra outer reference created');
}
ok(!$destroyed, 'extra outer reference keeps client alive');
$ref = undef;
is($msg->code, 7, 'dropping last outer reference marks outstanding message');
undef $msg;
ok($destroyed, 'inner object destroyed after held message is released');
