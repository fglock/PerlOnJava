use strict;
use warnings;

use Test::More tests => 4;
use threads;
use Hash::Util::FieldHash qw(fieldhash);
use Scalar::Util qw(weaken);

fieldhash my %object_ids;

{
    package Local::Deferred;
    use Scalar::Util qw(weaken);

    our %DEFERRED;
    our %QUOTED;

    sub make {
        my ($value) = @_;
        my ($deferred, $unquoted);
        my $quoted_info = { code => $value };
        my $deferred_info = [ sub { $quoted_info->{code} }, \$unquoted ];
        $deferred = sub {
            $quoted_info if 0;
            $deferred_info if 0;
            Local::Deferred::invoke($deferred_info->[4]);
        };
        weaken($deferred_info->[4] = $deferred);
        weaken($quoted_info->{deferred} = $deferred);
        weaken($DEFERRED{"$deferred"} = $deferred_info);
        weaken($QUOTED{"$deferred"} = $quoted_info);
        return $deferred;
    }

    sub quoted_from {
        my ($deferred) = @_;
        my $info = $QUOTED{"$deferred"} or return undef;
        return $info->{code}
            if $info->{deferred} && $info->{deferred} eq $deferred;
        return undef;
    }

    sub invoke {
        my ($deferred) = @_;
        my $info = $DEFERRED{"$deferred"} or return undef;
        return undef unless $info->[4] && $info->[4] eq $deferred;
        return ${$info->[1]} if ${$info->[1]};
        return ${$info->[1]} = $info->[0]->();
    }

    sub CLONE {
        %DEFERRED = map {
            defined $_ && $_->[4] ? ($_->[4] => $_) : ()
        } values %DEFERRED;
        weaken($_) for values %DEFERRED;

        %QUOTED = map {
            defined $_ && $_->{deferred} ? ($_->{deferred} => $_) : ()
        } values %QUOTED;
        weaken($_) for values %QUOTED;
    }
}

my $object = bless {}, 'Local::Object';
$object_ids{$object} = 'stable-id';
my $deferred = Local::Deferred::make('deferred-result');

my $worker = threads->create(sub {
    return [ $object_ids{$object}, Local::Deferred::quoted_from($deferred),
        $deferred->() ];
});
my $result = $worker->join;

is $result->[0], 'stable-id', 'fieldhash value follows object into child thread';
is $result->[1], 'deferred-result', 'quoted metadata is restored by CLONE';
is $result->[2], 'deferred-result', 'deferred sub remains callable after clone';
is $deferred->(), 'deferred-result',
    'parent deferred sub remains usable after child clone';
