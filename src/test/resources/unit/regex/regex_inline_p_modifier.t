use strict;
use warnings;
use Test::More tests => 15;

{
    my $subject = '012-345-6789';
    ok($subject =~ /(?p)345/, 'standalone inline p matches');
    is(${^PREMATCH}, '012-', 'standalone inline p exposes prematch');
    is(${^MATCH}, '345', 'standalone inline p exposes match');
    is(${^POSTMATCH}, '-6789', 'standalone inline p exposes postmatch');
}

{
    my $subject = '012-345-6789';
    ok($subject =~ /(?p:345)/, 'scoped inline p matches');
    is(${^MATCH}, '345', 'scoped inline p exposes match');
}

{
    my $subject = '012-AbC-6789';
    ok($subject =~ /(?ip:abc)/, 'inline p composes with matcher modifiers');
    is(${^MATCH}, 'AbC', 'combined inline modifiers expose exact match');
}

{
    my $literal = '(?p)';
    ok($literal =~ /\(\?p\)/, 'escaped modifier spelling remains literal');
    ok(!defined ${^MATCH}, 'escaped modifier spelling does not enable p');
}

{
    my $literal = 'p';
    ok($literal =~ /[(?p)]/, 'modifier spelling in a class remains literal');
    ok(!defined ${^MATCH}, 'character-class spelling does not enable p');
}

{
    my $callback_match;
    my $subject = 'a';
    is($subject =~ s/(?p:a(?{ $callback_match = ${^MATCH} }))/b/, 1,
        'inline p substitution callback executes');
    is($callback_match, 'a', 'inline p is visible to substitution callback');
    is($subject, 'b', 'inline p substitution retains normal replacement behavior');
}
