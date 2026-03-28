#!/usr/bin/env jperl
use strict;
use warnings;

package Point {
    use Moo;
    has x => (is => 'ro', default => 0);
    has y => (is => 'ro', default => 0);
    
    sub distance {
        my $self = shift;
        return sqrt($self->x ** 2 + $self->y ** 2);
    }
}

my $p = Point->new(x => 3, y => 4);
print "Point: (", $p->x, ", ", $p->y, ")\n";
print "Distance from origin: ", $p->distance, "\n";
