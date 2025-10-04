#!/usr/bin/perl
use v5.38;
use feature 'class';
no warnings 'experimental::class';

class Point {
    field $x :param :reader;
    field $y :param :reader = 0;
    field $moves = 0;
    
    method move($dx, $dy) {
        $self->{x} += $dx;
        $self->{y} += $dy;
        $self->{moves}++;
    }
    
    method distance() {
        return sqrt($self->{x} ** 2 + $self->{y} ** 2);
    }
    
    method info() {
        return "Point at ($self->{x}, $self->{y}) - moved $self->{moves} times";
    }
}

# Test instantiation
my $p = Point->new(x => 3, y => 4);
print "Initial x: ", $p->x, "\n";
print "Initial y: ", $p->y, "\n";

# Test methods
$p->move(2, 1);
print "After move - x: ", $p->x, ", y: ", $p->y, "\n";
print "Distance: ", $p->distance(), "\n";
print "Info: ", $p->info(), "\n";

# Test default values
my $p2 = Point->new(x => 10);  # y should default to 0
print "P2 x: ", $p2->x, ", y: ", $p2->y, "\n";
