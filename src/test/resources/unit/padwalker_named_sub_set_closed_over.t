use strict;
use warnings;
use Test::More tests => 3;
use PadWalker qw(set_closed_over);

no warnings 'closure';
sub make_named_capture {
    my $named_captured_lexical = 'original';
    sub main::named_capture_for_padwalker { $named_captured_lexical }
    return \&main::named_capture_for_padwalker;
}

my $named_capture = make_named_capture();
my $replacement = 'replacement';
set_closed_over($named_capture, { '$named_captured_lexical' => \$replacement });
is($named_capture->(), 'replacement', 'named sub reads its rebound captured lexical');

$replacement = 'updated';
is($named_capture->(), 'updated', 'named sub observes later changes to the replacement');

is($replacement, 'updated', 'replacement lexical remains directly mutable');
