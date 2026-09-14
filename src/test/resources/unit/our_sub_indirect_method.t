use strict;
use warnings;
use feature 'lexical_subs';
no warnings 'experimental::lexical_subs';
use Test::More;

sub OurSubMethodTarget::h { 4242 }

{
    my $called;
    our sub h { ++$called; 4343 }

    is((h OurSubMethodTarget), 4242,
        'our-sub alias does not suppress indirect method syntax');
    is $called, undef,
        'indirect method syntax does not invoke the our-sub alias';
    is h(), 4343, 'ordinary direct call still uses the our-sub alias';
}

done_testing;
