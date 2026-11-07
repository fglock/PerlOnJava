use strict;
use warnings;
use feature 'state';

use Scalar::Util qw(weaken);
use Test::More tests => 5;

{
    package Local::VoidDiscardLoop;

    sub new { bless {}, shift }
}

{
    package Local::VoidDiscardPromise;

    our $destroyed = 0;

    sub new {
        my $class = ref($_[0]) || $_[0];
        return bless {}, $class;
    }

    sub ioloop {
        return exists $_[0]{ioloop}
            ? $_[0]{ioloop}
            : (
                ref($_[0]{ioloop} = main::loop_singleton())
                    && Scalar::Util::weaken($_[0]{ioloop}),
                $_[0]{ioloop}
            )
            if @_ == 1;
        ref($_[0]{ioloop} = $_[1]) && Scalar::Util::weaken($_[0]{ioloop});
        return $_[0];
    }

    sub clone { $_[0]->new->ioloop($_[0]->ioloop) }

    sub then {
        my $self = shift;
        my $next = $self->clone;
        $main::weak_next = $next;
        Scalar::Util::weaken($main::weak_next);
        push @{$self->{callbacks}}, sub { return $next->ioloop };
        return $next;
    }

    sub DESTROY { $destroyed++ }
}

sub loop_singleton {
    state $loop = Local::VoidDiscardLoop->new;
    return $loop;
}

our ($weak_source, $weak_next);
my $source = Local::VoidDiscardPromise->new;
$weak_source = $source;
weaken($weak_source);
$source->then;

ok(defined $weak_next, 'stored closure keeps a void-discarded return alive');
is($Local::VoidDiscardPromise::destroyed, 0,
    'void-discard cleanup does not destroy the closure-owned return');
isa_ok($source->{callbacks}[0]->(), 'Local::VoidDiscardLoop',
    'stored closure can still use the captured promise');

undef $source;
is($Local::VoidDiscardPromise::destroyed, 2,
    'source and closure-owned return are destroyed with their final owner');
ok(!defined($weak_source) && !defined($weak_next),
    'weak observers clear after both promises are released');
