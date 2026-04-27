#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 12;
use XML::Parser;

# Regression test for XML::Parser::Expat current_element / Context push-pop
# timing.  Real libexpat updates Context AFTER the Start handler returns
# and BEFORE the End handler runs, so:
#   - inside StartTag: current_element returns the *parent* element
#     (or undef at the root)
#   - inside EndTag:   current_element returns the parent (or undef at root)
#
# Previously PerlOnJava pushed before Start and popped after End, which broke
# XML::SemanticDiff's empty-element CData handling (returned '' instead of
# undef).  See dev/modules/active_resource.md.

my $xml = qq{<?xml version="1.0"?>\n<root>\n<el2></el2>\n</root>\n};

my @events;
package Recorder;
sub StartDocument { }
sub StartTag {
    my ($e, $name) = @_;
    push @events, "Start:$name:cur=" . ($e->current_element // 'undef')
                . ":depth=" . $e->depth;
}
sub EndTag {
    my ($e, $name) = @_;
    push @events, "End:$name:cur=" . ($e->current_element // 'undef')
                . ":depth=" . $e->depth;
}
sub Text {
    my ($e) = @_;
    my $text = $_;
    $text =~ s/\n/\\n/g;
    push @events, "Text:cur=" . ($e->current_element // 'undef')
                . ":text='$text'";
}
package main;

XML::Parser->new(Style => 'Stream', Pkg => 'Recorder')->parse($xml);

# Inside StartTag root: Context is still empty.
is($events[0], 'Start:root:cur=undef:depth=0',
   'StartTag of root sees empty Context (current=undef, depth=0)');

# After Start root returned, Context = [root]; the inter-element \n is
# attributed to root.
is($events[1], q{Text:cur=root:text='\n'},
   'inter-element text attributed to parent (root) not the next sibling');

# StartTag el2 sees root as current_element (el2 not yet pushed).
is($events[2], 'Start:el2:cur=root:depth=1',
   'StartTag of el2 sees parent in Context (current=root, depth=1)');

# EndTag el2 sees Context already popped back to root.
is($events[3], 'End:el2:cur=root:depth=1',
   'EndTag of el2 sees parent in Context (current=root, depth=1)');

# Trailing \n attributed to root.
is($events[4], q{Text:cur=root:text='\n'},
   'trailing text attributed to root');

# EndTag root: Context already popped to empty.
is($events[5], 'End:root:cur=undef:depth=0',
   'EndTag of root sees empty Context (current=undef, depth=0)');

is(scalar @events, 6, 'exactly 6 events recorded');

# Nested case: <a><b>x</b></a>
@events = ();
XML::Parser->new(Style => 'Stream', Pkg => 'Recorder')->parse('<a><b>x</b></a>');

is($events[0], 'Start:a:cur=undef:depth=0', 'nested: Start a sees empty');
is($events[1], 'Start:b:cur=a:depth=1',     'nested: Start b sees a as parent');
is($events[2], q{Text:cur=b:text='x'},      'nested: Text inside b sees b');
is($events[3], 'End:b:cur=a:depth=1',       'nested: End b sees a as parent');
is($events[4], 'End:a:cur=undef:depth=0',   'nested: End a sees empty');
