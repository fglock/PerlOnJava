use strict;
use warnings;
use Test::More tests => 4;

my $loader_spec = qr/
    ^ (?<name> \w+ (?: :: \w+)* )
    (?:
        ::
        (?<call>
            \w+
            (?: (?<P>[(]) | = )
            (?<arg> [^)]* )
            (?(<P>) [)] | )
        )
    )?
    $
/x;

ok('Package::method(argument)' =~ $loader_spec,
    'named-capture conditional takes its parenthesized branch');
is($+{arg}, 'argument', 'parenthesized loader argument is captured');
ok('Package::method=argument' =~ $loader_spec,
    'named-capture conditional takes its empty branch');
is($+{arg}, 'argument', 'equals loader argument is captured');
