use strict;
use warnings;

use Test::More;
use B::Deparse;
use Scalar::Util qw(weaken);

{
    package Sub::Quote;

    our %QUOTED;

    sub quote_sub {
        my ($name, $source) = @_;
        my $quoted_info = {
            name => $name,
            code => $source,
        };

        my $constructor = sub {
            ($quoted_info) if 0;
            my $marker = "quoted constructor survives";
            42;
        };

        Scalar::Util::weaken($QUOTED{$constructor} = $quoted_info);
        return $constructor;
    }

    sub quoted_from_sub {
        my ($sub) = @_;
        my $quoted_info = $QUOTED{$sub || ''} or return undef;
        return [ $quoted_info->{name}, $quoted_info->{code} ];
    }
}

{
    package Sub::Defer;

    our %DEFERRED;

    sub defer_sub {
        my ($target, $maker) = @_;
        my $undeferred;
        my $deferred;

        $deferred = sub {
            $undeferred ||= undefer_sub($deferred);
            goto &$undeferred;
        };

        $DEFERRED{$deferred} = [ $target, $maker, \$undeferred, $deferred ];
        no strict 'refs';
        *{$target} = $deferred;
        return $deferred;
    }

    sub undefer_sub {
        my ($deferred) = @_;
        my $info = $DEFERRED{$deferred} or return $deferred;
        my ($target, $maker, $undeferred_ref) = @$info;
        return $$undeferred_ref if $$undeferred_ref;

        my $made = $$undeferred_ref = $maker->();
        no strict 'refs';
        *{$target} = $made if defined $target;
        $DEFERRED{$made} = $info;
        return $made;
    }
}

Sub::Defer::defer_sub 'QuotedConstructor::new' => sub {
    my $constructor = Sub::Quote::quote_sub(
        'QuotedConstructor::new',
        'my $marker = "quoted constructor survives"; 42',
    );
    $constructor;
};

is(QuotedConstructor->new, 42, 'deferred quoted constructor remains callable');

my $source = B::Deparse->new->coderef2text(QuotedConstructor->can('new'));
like($source, qr/quoted constructor survives/, 'deferred quoted constructor metadata survives temporary cleanup');

done_testing;
