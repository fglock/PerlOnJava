#!/usr/bin/env perl
use strict;
use warnings;
use Test::More;

BEGIN {
    if (!eval {
        require Sub::Quote;
        Sub::Quote->import(qw(qsub quoted_from_sub unquote_sub));
        1;
    }) {
        require Scalar::Util;

        our %QUOTED;

        *qsub = sub {
            my ($code) = @_;
            my $quoted_info = { code => $code };
            my $unquoted;
            Scalar::Util::weaken($quoted_info->{unquoted} = \$unquoted);

            my $deferred;
            $deferred = sub {
                $unquoted if 0;
                goto &{ unquote_sub($quoted_info->{deferred}) };
            };

            Scalar::Util::weaken($quoted_info->{deferred} = $deferred);
            Scalar::Util::weaken($QUOTED{$deferred} = $quoted_info);
            return $deferred;
        };

        *quoted_from_sub = sub {
            my ($sub) = @_;
            my $quoted_info = $QUOTED{$sub || ''} or return undef;
            my $unquoted = $quoted_info->{unquoted};
            $unquoted &&= $$unquoted;
            return [ undef, $quoted_info->{code}, undef, $unquoted, $quoted_info->{deferred} ];
        };

        *unquote_sub = sub {
            my ($sub) = @_;
            my $quoted_info = $QUOTED{$sub} or return undef;
            my $unquoted = $quoted_info->{unquoted};
            unless ($unquoted && $$unquoted) {
                my $_QUOTED = $quoted_info;
                my $_UNQUOTED = $unquoted;
                $$unquoted = sub {
                    ($_QUOTED, $_UNQUOTED) if 0;
                    return $_[0];
                };
                Scalar::Util::weaken($QUOTED{$$unquoted} = $quoted_info);
            }
            return $$unquoted;
        };
    }
}

plan tests => 3;

my $quoted = qsub(q{ $_[0] });
ok(quoted_from_sub($quoted), 'qsub metadata survives after deferred sub creation');

my $unquoted = unquote_sub($quoted);
ok($unquoted, 'qsub can be unquoted after metadata lookup');
is($unquoted->('ok'), 'ok', 'unquoted qsub remains callable');
