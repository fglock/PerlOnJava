use strict;
use warnings;
use Test::More tests => 2;
use Scalar::Util qw(blessed refaddr weaken);

{
    package Local::CallLog;

    sub new { bless {}, shift }

    sub _key {
        return Scalar::Util::blessed($_[0])
            ? Scalar::Util::refaddr($_[0]) : $_[0];
    }

    {
        my %calls;

        sub log_call {
            my ($self, @args) = @_;
            push @{ $calls{_key($self)} ||= [] }, \@args;
        }

        sub DESTROY {
            my $self = shift;
            delete $calls{_key($self)};
        }
    }
}

my $holder = Local::CallLog->new;
my $target = Local::CallLog->new;
my $weak_holder = $holder;
my $weak_target = $target;
weaken($weak_holder);
weaken($weak_target);

$holder->log_call($target);
undef $target;
is(ref($weak_target), 'Local::CallLog',
    'object survives through nested aggregate in block lexical');

undef $holder;
ok(!ref($weak_target) && !ref($weak_holder),
    'captured call log releases objects with its owning entry');
