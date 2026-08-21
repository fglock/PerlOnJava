use strict;
use warnings;
use utf8;
use Test::More;
use threads;
use lib 'src/test/resources/unit/regex';

local $ENV{JPERL_UNIMPLEMENTED} = 'warn';

sub invalid_messages {
    {
        use LocalPermissiveCharnames;

        my @sources = (
            q{q() =~ /\N{4F}/},
            q{q() =~ /\N{COM,MA}/},
            q{q() =~ /\N{A×O}/},
            q{use utf8; q() =~ /\N{7 CITIES OF GOLD}/},
            q{use utf8; q() =~ /\N{SHARP #}/},
            q{use utf8; q() =~ /\N{A ÷ HOUSE}/},
            q{use utf8; q() =~ /\N{٤ HORSEMEN}/},
            q{use utf8; q() =~ /\N{A 💩 WOULD SMELL}/},
        );
        my @messages;
        for my $source (@sources) {
            eval $source;
            push @messages, $@;
        }
        return \@messages;
    }
}

my $expected = qr/Invalid character in \\N\{\.\.\.\}/;
my $parent = invalid_messages();
like $_, $expected, 'invalid custom charname is diagnosed in parent eval'
        for @$parent;

my $child = threads->create(sub { invalid_messages() })->join;
like $_, $expected, 'invalid custom charname is diagnosed in child eval'
        for @$child;

done_testing;
