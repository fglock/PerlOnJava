use strict;
use warnings;
use Test::More tests => 3;
use Scalar::Util qw(weaken);

our %DEFERRED;
our $HOLDER;

sub my_undefer {
    my ($deferred) = @_;
    my $info = $DEFERRED{$deferred} or return $deferred;
    my ($maker, $undeferred_ref) = @$info;
    return $$undeferred_ref if $$undeferred_ref;
    $$undeferred_ref = $maker->();
    return $$undeferred_ref;
}

sub my_defer {
    my ($maker) = @_;
    my $deferred;
    my $undeferred;
    my $info = [ $maker, \$undeferred ];

    $deferred = sub {
        $undeferred ||= my_undefer($info->[2]);
        goto &$undeferred;
    };

    weaken($info->[2] = $deferred);
    weaken($DEFERRED{$deferred} = $info);
    return $deferred;
}

sub qsub_like {
    return my_defer(sub { sub { return 'ok' } });
}

$HOLDER = { isa => qsub_like() };

my $code = $HOLDER->{isa};
ok($DEFERRED{$code}, 'deferred info exists before gc');
undef $code;

Internals::jperl_gc() if defined &Internals::jperl_gc;

my $survived = $DEFERRED{$HOLDER->{isa}};
ok($survived, 'deferred info survives gc through holder metadata');

SKIP: {
    skip 'deferred info was cleared', 1 unless $survived;
    is($HOLDER->{isa}->(), 'ok', 'deferred code can undefer after gc');
}
